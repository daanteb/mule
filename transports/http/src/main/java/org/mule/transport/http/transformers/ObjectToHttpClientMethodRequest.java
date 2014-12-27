/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.http.transformers;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.BasicNameValuePair;
import org.mule.RequestContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleMessage;
import org.mule.api.MuleMessageCollection;
import org.mule.api.config.MuleProperties;
import org.mule.api.transformer.DataType;
import org.mule.api.transformer.TransformerException;
import org.mule.api.transport.OutputHandler;
import org.mule.api.transport.PropertyScope;
import org.mule.message.ds.StringDataSource;
import org.mule.transformer.AbstractMessageTransformer;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.transport.NullPayload;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.transport.http.StreamPayloadRequestEntity;
import org.mule.transport.http.i18n.HttpMessages;
import org.mule.transport.http.multipart.PartDataSource;
import org.mule.util.IOUtils;
import org.mule.util.ObjectUtils;
import org.mule.util.SerializationUtils;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.activation.URLDataSource;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <code>ObjectToHttpClientMethodRequest</code> transforms a MuleMessage into a
 * HttpClient HttpMethod that represents an HttpRequest.
 */
public class ObjectToHttpClientMethodRequest extends AbstractMessageTransformer
{
    public ObjectToHttpClientMethodRequest()
    {
        setReturnDataType(DataTypeFactory.create(HttpRequest.class));
        registerSourceType(DataTypeFactory.MULE_MESSAGE);
        registerSourceType(DataTypeFactory.BYTE_ARRAY);
        registerSourceType(DataTypeFactory.STRING);
        registerSourceType(DataTypeFactory.INPUT_STREAM);
        registerSourceType(DataTypeFactory.create(OutputHandler.class));
        registerSourceType(DataTypeFactory.create(NullPayload.class));
        registerSourceType(DataTypeFactory.create(Map.class));
    }

    @Override
    public Object transformMessage(MuleMessage msg, String outputEncoding) throws TransformerException
    {
        String method = detectHttpMethod(msg);

        try
        {
            HttpRequestBase httpMethod;

            if (HttpConstants.METHOD_GET.equals(method))
            {
                httpMethod = createGetMethod(msg, outputEncoding);
            }
            else if (HttpConstants.METHOD_POST.equalsIgnoreCase(method))
            {
                httpMethod = createPostMethod(msg, outputEncoding);
            }
            else if (HttpConstants.METHOD_PUT.equalsIgnoreCase(method))
            {
                httpMethod = createPutMethod(msg, outputEncoding);
            }
            else if (HttpConstants.METHOD_DELETE.equalsIgnoreCase(method))
            {
                httpMethod = createDeleteMethod(msg);
            }
            else if (HttpConstants.METHOD_HEAD.equalsIgnoreCase(method))
            {
                httpMethod = createHeadMethod(msg);
            }
            else if (HttpConstants.METHOD_OPTIONS.equalsIgnoreCase(method))
            {
                httpMethod = createOptionsMethod(msg);
            }
            else if (HttpConstants.METHOD_TRACE.equalsIgnoreCase(method))
            {
                httpMethod = createTraceMethod(msg);
            }
            else if (HttpConstants.METHOD_PATCH.equalsIgnoreCase(method))
            {
                httpMethod = createPatchMethod(msg, outputEncoding);
            }
            else
            {
                throw new TransformerException(HttpMessages.unsupportedMethod(method));
            }

            //TODO(pablo.kraan): HTTPCLIENT - fix this
            //// Allow the user to set HttpMethodParams as an object on the message
            //final HttpMethodParams params = (HttpMethodParams) msg.removeProperty(
            //    HttpConnector.HTTP_PARAMS_PROPERTY, PropertyScope.OUTBOUND);
            //if (params != null)
            //{
            //    httpMethod.setParams(params);
            //}
            //else
            //{
            httpMethod.setProtocolVersion(BasicLineParser.parseProtocolVersion(
                msg.getOutboundProperty(HttpConnector.HTTP_VERSION_PROPERTY, HttpConstants.HTTP11), null));
            //}
            //
            setHeaders(httpMethod, msg);

            return httpMethod;
        }
        catch (final Exception e)
        {
            throw new TransformerException(this, e);
        }
    }

    protected String detectHttpMethod(MuleMessage msg)
    {
        String method = msg.getOutboundProperty(HttpConnector.HTTP_METHOD_PROPERTY, null);
        if (method == null)
        {
            method = msg.getInvocationProperty(HttpConnector.HTTP_METHOD_PROPERTY, HttpConstants.METHOD_POST);
        }
        return method;
    }

    protected HttpRequestBase createGetMethod(MuleMessage msg, String outputEncoding) throws Exception
    {
        URIBuilder uri = getURI(msg);

        return new HttpGet(uri.toString());
    }

    protected HttpRequestBase createPostMethod(MuleMessage msg, String outputEncoding) throws Exception
    {
        URIBuilder uri = getURI(msg);
        HttpPost postMethod = new HttpPost(uri.toString());

        String bodyParameterName = getBodyParameterName(msg);
        Object src = msg.getPayload();
        if (src instanceof Map)
        {
            List<NameValuePair> params = new ArrayList<NameValuePair>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) src).entrySet())
            {
                params.add(new BasicNameValuePair(entry.getKey().toString(), entry.getValue().toString()));
                postMethod.setEntity(new UrlEncodedFormEntity(params));
            }
        }
        else if (bodyParameterName != null)
        {
            postMethod.setEntity(new UrlEncodedFormEntity(Collections.singletonList(new BasicNameValuePair(
                bodyParameterName, src.toString()))));;
        }
        else
        {
            setupEntityMethod(src, outputEncoding, msg, postMethod);
        }
        checkForContentType(msg, postMethod);

        return postMethod;
    }

    private void checkForContentType(MuleMessage msg, HttpEntityEnclosingRequest method)
    {
        // if a content type was specified on the endpoint, use it
        String outgoingContentType = msg.getInvocationProperty(HttpConstants.HEADER_CONTENT_TYPE);
        if (outgoingContentType != null)
        {
            method.setHeader(HttpConstants.HEADER_CONTENT_TYPE, outgoingContentType);
        }
    }

    protected String getBodyParameterName(MuleMessage message)
    {
        String bodyParameter = message.getOutboundProperty(HttpConnector.HTTP_POST_BODY_PARAM_PROPERTY);
        if (bodyParameter == null)
        {
            bodyParameter = message.getInvocationProperty(HttpConnector.HTTP_POST_BODY_PARAM_PROPERTY);
        }
        return bodyParameter;
    }

    protected HttpRequestBase createPutMethod(MuleMessage msg, String outputEncoding) throws Exception
    {
        URIBuilder uri = getURI(msg);
        HttpPut putMethod = new HttpPut(uri.toString());

        Object payload = msg.getPayload();
        setupEntityMethod(payload, outputEncoding, msg, putMethod);
        checkForContentType(msg, putMethod);

        return putMethod;
    }

    protected HttpRequestBase createDeleteMethod(MuleMessage message) throws Exception
    {
        URIBuilder uri = getURI(message);
        return new HttpDelete(uri.toString());
    }

    protected HttpRequestBase createHeadMethod(MuleMessage message) throws Exception
    {
        URIBuilder uri = getURI(message);
        return new HttpHead(uri.toString());
    }

    protected HttpRequestBase createOptionsMethod(MuleMessage message) throws Exception
    {
        URIBuilder uri = getURI(message);
        return new HttpOptions(uri.toString());
    }

    protected HttpRequestBase createTraceMethod(MuleMessage message) throws Exception
    {
        URIBuilder uri = getURI(message);
        return new HttpTrace(uri.toString());
    }

    protected HttpRequestBase createPatchMethod(MuleMessage message, String outputEncoding) throws Exception
    {
        URIBuilder uri = getURI(message);
        HttpPatch patchMethod = new HttpPatch(uri.toString());

        Object payload = message.getPayload();
        setupEntityMethod(payload, outputEncoding, message, patchMethod);
        checkForContentType(message, patchMethod);
        return patchMethod;
    }

    protected URIBuilder getURI(MuleMessage message) throws URISyntaxException, TransformerException
    {
        String endpointAddress = message.getOutboundProperty(MuleProperties.MULE_ENDPOINT_PROPERTY, null);
        if (endpointAddress == null)
        {
            throw new TransformerException(
                HttpMessages.eventPropertyNotSetCannotProcessRequest(MuleProperties.MULE_ENDPOINT_PROPERTY),
                this);
        }

        //new URIBuilder().
        URIBuilder withUserInfo = new URIBuilder(endpointAddress);

        return new URIBuilder()
                .setScheme(withUserInfo.getScheme())
                .setHost(withUserInfo.getHost())
                .setPort(withUserInfo.getPort())
                .setPath(withUserInfo.getPath())
                .setParameters(withUserInfo.getQueryParams())
                .setFragment(withUserInfo.getFragment());
    }

    protected void setupEntityMethod(Object src,
                                     String encoding,
                                     MuleMessage msg,
                                     HttpEntityEnclosingRequest postMethod)
        throws UnsupportedEncodingException, TransformerException
    {
        // Dont set a POST payload if the body is a Null Payload.
        // This way client calls can control if a POST body is posted explicitly
        if (!(msg.getPayload() instanceof NullPayload))
        {
            String outboundMimeType = (String) msg.getProperty(HttpConstants.HEADER_CONTENT_TYPE,
                PropertyScope.OUTBOUND);
            if (outboundMimeType == null)
            {
                outboundMimeType = (getEndpoint() != null ? getEndpoint().getMimeType() : null);
            }
            if (outboundMimeType == null)
            {
                outboundMimeType = HttpConstants.DEFAULT_CONTENT_TYPE;
                logger.info("Content-Type not set on outgoing request, defaulting to: " + outboundMimeType);
            }

            if (encoding != null && !"UTF-8".equals(encoding.toUpperCase())
                && outboundMimeType.indexOf("charset") == -1)
            {
                outboundMimeType += "; charset=" + encoding;
            }

            // Ensure that we have a cached representation of the message if we're
            // using HTTP 1.0
            final String httpVersion = msg.getOutboundProperty(HttpConnector.HTTP_VERSION_PROPERTY,
                HttpConstants.HTTP11);
            if (HttpConstants.HTTP10.equals(httpVersion))
            {
                try
                {
                    if (msg instanceof MuleMessageCollection)
                    {
                        src = msg.getPayload(DataType.BYTE_ARRAY_DATA_TYPE);
                    }
                    else
                    {
                        src = msg.getPayloadAsBytes();
                    }
                }
                catch (final Exception e)
                {
                    throw new TransformerException(this, e);
                }
            }

            if (msg.getOutboundAttachmentNames() != null && msg.getOutboundAttachmentNames().size() > 0)
            {
                try
                {
                    postMethod.setEntity(createMultiPart(msg));
                    return;
                }
                catch (final Exception e)
                {
                    throw new TransformerException(this, e);
                }
            }
            if (src instanceof String)
            {
                postMethod.setEntity(new StringEntity(src.toString(), outboundMimeType, encoding));
                return;
            }

            if (src instanceof InputStream)
            {
                postMethod.setEntity(new InputStreamEntity((InputStream) src, ContentType.create(outboundMimeType)));
            }
            else if (src instanceof byte[])
            {
                Charset o = null;
                postMethod.setEntity(new ByteArrayEntity((byte[]) src, ContentType.create(outboundMimeType)));
            }
            else if (src instanceof OutputHandler)
            {
                final MuleEvent event = RequestContext.getEvent();
                postMethod.setEntity(new StreamPayloadRequestEntity((OutputHandler) src, event));
            }
            else
            {
                final byte[] buffer = SerializationUtils.serialize((Serializable) src);
                postMethod.setEntity(new ByteArrayEntity(buffer, ContentType.create(outboundMimeType)));
            }
        }
        else if (msg.getOutboundAttachmentNames() != null && msg.getOutboundAttachmentNames().size() > 0)
        {
            try
            {
                postMethod.setEntity(createMultiPart(msg));
            }
            catch (Exception e)
            {
                throw new TransformerException(this, e);
            }
        }
    }

    protected void setHeaders(HttpRequest httpMethod, MuleMessage msg) throws TransformerException
    {
        for (String headerName : msg.getOutboundPropertyNames())
        {
            String headerValue = ObjectUtils.getString(msg.getOutboundProperty(headerName), null);

            if (headerName.startsWith(MuleProperties.PROPERTY_PREFIX))
            {
                // Define Mule headers a custom headers
                headerName = new StringBuilder(30).append("X-").append(headerName).toString();
                httpMethod.addHeader(headerName, headerValue);

            }

            else if (!HttpConstants.RESPONSE_HEADER_NAMES.containsKey(headerName)
                     && !HttpConnector.HTTP_INBOUND_PROPERTIES.contains(headerName)
                     && !HttpConnector.HTTP_COOKIES_PROPERTY.equals(headerName))
            {

                httpMethod.addHeader(headerName, headerValue);
            }
        }
    }

    protected HttpEntity createMultiPart(MuleMessage msg) throws Exception
    {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        for (final String attachmentNames : msg.getOutboundAttachmentNames())
        {
            String fileName = attachmentNames;
            final DataHandler dh = msg.getOutboundAttachment(attachmentNames);
            if (dh.getDataSource() instanceof StringDataSource)
            {
                final StringDataSource ds = (StringDataSource) dh.getDataSource();
                ContentBody contentBody = new StringBody(IOUtils.toString(ds.getInputStream()), ContentType.create(dh.getContentType()));
                builder.addPart(ds.getName(), contentBody);
            }
            else
            {
                if (dh.getDataSource() instanceof FileDataSource)
                {
                    fileName = ((FileDataSource) dh.getDataSource()).getFile().getName();
                }
                else if (dh.getDataSource() instanceof URLDataSource)
                {
                    fileName = ((URLDataSource) dh.getDataSource()).getURL().getFile();
                    // Don't use the whole file path, just the file name
                    final int x = fileName.lastIndexOf("/");
                    if (x > -1)
                    {
                        fileName = fileName.substring(x + 1);
                    }
                } else if (dh.getDataSource() instanceof PartDataSource) {
                    String contentDisposition = ((PartDataSource) dh.getDataSource()).getPart().getHeader("content-disposition");
                    if (contentDisposition != null) {
                        String[] contentDispositionParts = contentDisposition.split(";");
                        for (String cdp : contentDispositionParts) {
                            String[] keyValue = cdp.trim().split("=");
                            if ("filename".equals(keyValue[0].trim())) {
                                fileName = keyValue[1].trim().replace("\"", "");
                            }
                        }

                    }
                }
                ContentBody contentBody = new ByteArrayBody(IOUtils.toByteArray(dh.getInputStream()), ContentType.create(dh.getContentType()), fileName);
                builder.addPart(dh.getName(), contentBody);
            }
        }

        if (!(msg.getPayload() instanceof NullPayload))
        {
            ContentBody contentBody = new ByteArrayBody(msg.getPayloadAsBytes(), ContentType.DEFAULT_BINARY, "payload");
            builder.addPart("payload", contentBody);
        }

        return builder.build();
    }
}
