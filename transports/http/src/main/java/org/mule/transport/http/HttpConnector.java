/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.http;

import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.EndpointURI;
import org.mule.api.endpoint.ImmutableEndpoint;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.transport.MessageReceiver;
import org.mule.api.transport.NoReceiverForEndpointException;
import org.mule.config.i18n.CoreMessages;
import org.mule.transport.http.i18n.HttpMessages;
import org.mule.transport.http.ntlm.JCIFSNTLMSchemeFactory;
import org.mule.transport.http.util.IdleConnectionTimeoutThread;
import org.mule.transport.tcp.TcpConnector;
import org.mule.util.MapUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;

import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.config.SocketConfig.Builder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.KerberosSchemeFactory;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

/**
 * <code>HttpConnector</code> provides a way of receiving and sending http requests
 * and responses. The Connector itself handles dispatching http requests. The
 * <code>HttpMessageReceiver</code> handles the receiving requests and processing
 * of headers This endpoint recognises the following properties - <p/>
 * <ul>
 * <li>hostname - The hostname to send and receive http requests</li>
 * <li>port - The port to listen on. The industry standard is 80 and if this propert
 * is not set it will default to 80</li>
 * <li>proxyHostname - If you access the web through a proxy, this holds the server
 * address</li>
 * <li>proxyPort - The port the proxy is configured on</li>
 * <li>proxyUsername - If the proxy requires authentication supply a username</li>
 * <li>proxyPassword - If the proxy requires authentication supply a password</li>
 * </ul>
 */

public class HttpConnector extends TcpConnector
{

    public static final String HTTP = "http";
    public static final String HTTP_PREFIX = "http.";

    /**
     * MuleEvent property to pass back the status for the response
     */
    public static final String HTTP_STATUS_PROPERTY = HTTP_PREFIX + "status";
    public static final String HTTP_VERSION_PROPERTY = HTTP_PREFIX + "version";

    /**
     * @deprecated Instead users can now add properties to the outgoing request using the OUTBOUND property scope on the message.
     */
    @Deprecated
    public static final String HTTP_CUSTOM_HEADERS_MAP_PROPERTY = HTTP_PREFIX + "custom.headers";

    /**
     * Encapsulates all the HTTP headers
     */
    public static final String HTTP_HEADERS = HTTP_PREFIX + "headers";

    /**
     * Stores the HTTP query parameters received, supports multiple values per key and both query parameter key and
     * value are unescaped
     */
    public static final String HTTP_QUERY_PARAMS = HTTP_PREFIX + "query.params";

    public static final String HTTP_QUERY_STRING = HTTP_PREFIX + "query.string";

    public static final String HTTP_METHOD_PROPERTY = HTTP_PREFIX + "method";

    /**
     * The path and query portions of the URL being accessed.
     */
    public static final String HTTP_REQUEST_PROPERTY = HTTP_PREFIX + "request";

    /**
     * The path portion of the URL being accessed. No query string is included.
     */
    public static final String HTTP_REQUEST_PATH_PROPERTY = HTTP_PREFIX + "request.path";

    /**
     * The context path of the endpoint being accessed. This is the path that the
     * HTTP endpoint is listening on.
     */
    public static final String HTTP_CONTEXT_PATH_PROPERTY = HTTP_PREFIX + "context.path";

    /**
     * The context URI of the endpoint being accessed. This is the address that the
     * HTTP endpoint is listening on. It includes: [scheme]://[host]:[port][http.context.path]
     */
    public static final String HTTP_CONTEXT_URI_PROPERTY = HTTP_PREFIX + "context.uri";


    /**
     * The relative path of the URI being accessed in relation to the context path
     */
    public static final String HTTP_RELATIVE_PATH_PROPERTY = HTTP_PREFIX + "relative.path";

    public static final String HTTP_SERVLET_REQUEST_PROPERTY = HTTP_PREFIX + "servlet.request";
    public static final String HTTP_SERVLET_RESPONSE_PROPERTY = HTTP_PREFIX + "servlet.response";

    //TODO(pablo.kraan): HTTPCLIENT - fix this
    ///**
    // * Allows the user to set a {@link org.apache.http.client.params.HttpMethodParams} object in the client
    // * request to be set on the HttpMethod request object
    // */
    public static final String HTTP_PARAMS_PROPERTY = HTTP_PREFIX + "params";
    public static final String HTTP_GET_BODY_PARAM_PROPERTY = HTTP_PREFIX + "get.body.param";
    public static final String DEFAULT_HTTP_GET_BODY_PARAM_PROPERTY = "body";
    public static final String HTTP_POST_BODY_PARAM_PROPERTY = HTTP_PREFIX + "post.body.param";

    public static final String HTTP_DISABLE_STATUS_CODE_EXCEPTION_CHECK = HTTP_PREFIX + "disable.status.code.exception.check";
    public static final String HTTP_ENCODE_PARAMVALUE = HTTP_PREFIX + "encode.paramvalue";

    public static final Set<String> HTTP_INBOUND_PROPERTIES;

    static
    {
        Set<String> props = new HashSet<String>();
        props.add(HTTP_CONTEXT_PATH_PROPERTY);
        props.add(HTTP_GET_BODY_PARAM_PROPERTY);
        props.add(HTTP_METHOD_PROPERTY);
        props.add(HTTP_PARAMS_PROPERTY);
        props.add(HTTP_POST_BODY_PARAM_PROPERTY);
        props.add(HTTP_REQUEST_PROPERTY);
        props.add(HTTP_REQUEST_PATH_PROPERTY);
        props.add(HTTP_STATUS_PROPERTY);
        props.add(HTTP_VERSION_PROPERTY);
        props.add(HTTP_ENCODE_PARAMVALUE);
        HTTP_INBOUND_PROPERTIES = props;

        //TODO(pablo.kraan): HTTPCLIENT - fix this
        //AuthPolicy.registerAuthScheme(AuthPolicy.NTLM, NTLMScheme.class);
    }

    public static final String HTTP_COOKIE_SPEC_PROPERTY = "cookieSpec";
    public static final String HTTP_COOKIES_PROPERTY = "cookies";
    public static final String HTTP_ENABLE_COOKIES_PROPERTY = "enableCookies";

    public static final String COOKIE_SPEC_NETSCAPE = "netscape";
    public static final String COOKIE_SPEC_RFC2109 = "rfc2109";
    public static final String ROOT_PATH = "/";
    public static final int DEFAULT_CONNECTION_TIMEOUT = 2000;

    private String proxyHostname = null;

    private int proxyPort = HttpConstants.DEFAULT_HTTP_PORT;

    private String proxyUsername = null;

    private String proxyPassword = null;

    private boolean proxyNtlmAuthentication;

    private String cookieSpec;

    private boolean enableCookies = false;

    protected HttpClientConnectionManager clientConnectionManager;

    private IdleConnectionTimeoutThread connectionCleaner;

    private boolean disableCleanupThread;

    private org.mule.transport.http.HttpConnectionManager connectionManager;
    
    private boolean staleConnectionCheckEnabled = true;

    public HttpConnector(MuleContext context)
    {
        super(context);
    }

    @Override
    protected void doInitialise() throws InitialisationException
    {
        super.doInitialise();
        if (clientConnectionManager == null)
        {
            try
            {
                Builder socketConfigBuilder = SocketConfig.custom();
                if (getSocketSoLinger() != INT_VALUE_NOT_SET)
                {
                    socketConfigBuilder.setSoLinger(getSocketSoLinger());
                }
                if (getClientSoTimeout() != INT_VALUE_NOT_SET)
                {
                    socketConfigBuilder.setSoTimeout(getClientSoTimeout());
                }
                socketConfigBuilder.setTcpNoDelay(isSendTcpNoDelay());

                ConnectionConfig.Builder connectionConfigBuilder = ConnectionConfig.custom();
                if (getSendBufferSize() != INT_VALUE_NOT_SET)
                {
                    connectionConfigBuilder.setBufferSize(getSendBufferSize());
                }
                // TODO(dfeist): HTTPCLIENT - Receive buffer size is not used, only one buffer size is
                // specified.
                // if (getSendBufferSize() != INT_VALUE_NOT_SET)
                // {
                // params.setSendBufferSize(getSendBufferSize());
                // }

                PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(
                    getConnectionSocketFactoryRegistry());
                connManager.setDefaultConnectionConfig(connectionConfigBuilder.build());
                connManager.setDefaultSocketConfig(socketConfigBuilder.build());
                connManager.setMaxTotal(dispatchers.getMaxTotal());
                connManager.setDefaultMaxPerRoute(dispatchers.getMaxTotal());
                clientConnectionManager = connManager;

                String prop = System.getProperty("mule.http.disableCleanupThread");
                disableCleanupThread = prop != null && prop.equals("true");
                if (!disableCleanupThread)
                {
                    connectionCleaner = new IdleConnectionTimeoutThread();
                    connectionCleaner.setName("HttpClient-connection-cleaner-" + getName());
                    connectionCleaner.addConnectionManager(clientConnectionManager);
                    connectionCleaner.start();
                }
            }
            catch (Exception e)
            {
                throw new InitialisationException(e, this);
            }
        }
        // connection manager must be created during initialization due that devkit requires the connection
        // manager before start phase.
        // That's why it not manager only during stop/start phases and must be created also here.
        if (connectionManager == null)
        {
            try
            {
                connectionManager = new org.mule.transport.http.HttpConnectionManager(this,
                    getReceiverWorkManager());
            }
            catch (MuleException e)
            {
                throw new InitialisationException(
                    CoreMessages.createStaticMessage("failed creating http connection manager"), this);
            }
        }
    }

    protected Registry<ConnectionSocketFactory> getConnectionSocketFactoryRegistry()
        throws GeneralSecurityException
    {
        return RegistryBuilder.<ConnectionSocketFactory> create()
            .register("http", PlainConnectionSocketFactory.getSocketFactory())
            .build();
    }

    @Override
    protected void doDispose()
    {
        if (!disableCleanupThread)
        {
            connectionCleaner.shutdown();

        }
        if (this.connectionManager != null)
        {
            connectionManager.dispose();
            connectionManager = null;
        }
        clientConnectionManager.shutdown();
        clientConnectionManager = null;
        super.doDispose();
    }

    @Override
    protected void doStop() throws MuleException
    {
        this.connectionManager.dispose();
        this.connectionManager = null;
    }

    @Override
    protected void doStart() throws MuleException
    {
        super.doStart();
        if (this.connectionManager == null)
        {
            this.connectionManager = new org.mule.transport.http.HttpConnectionManager(this,getReceiverWorkManager());
        }
    }

    @Override
    public void registerListener(InboundEndpoint endpoint, MessageProcessor listener, FlowConstruct flowConstruct) throws Exception
    {
        if (endpoint != null)
        {
            Map endpointProperties = endpoint.getProperties();
            if (endpointProperties != null)
            {
                // normalize properties for HTTP
                Map newProperties = new HashMap(endpointProperties.size());
                for (Iterator entries = endpointProperties.entrySet().iterator(); entries.hasNext(); )
                {
                    Map.Entry entry = (Map.Entry) entries.next();
                    Object key = entry.getKey();
                    Object normalizedKey = HttpConstants.ALL_HEADER_NAMES.get(key);
                    if (normalizedKey != null)
                    {
                        // normalized property exists
                        key = normalizedKey;
                    }
                    newProperties.put(key, entry.getValue());
                }
                // set normalized properties back on the endpoint
                endpoint.getProperties().clear();
                endpoint.getProperties().putAll(newProperties);
            }
        }
        // proceed as usual
        super.registerListener(endpoint, listener, flowConstruct);
    }

    /**
     * The method determines the key used to store the receiver against.
     *
     * @param endpoint the endpoint being registered for the service
     * @return the key to store the newly created receiver against
     */
    @Override
    protected Object getReceiverKey(FlowConstruct flowConstruct, InboundEndpoint endpoint)
    {
        String key = endpoint.getEndpointURI().toString();
        int i = key.indexOf('?');
        if (i > -1)
        {
            key = key.substring(0, i);
        }
        return key;
    }

    /**
     * @see org.mule.api.transport.Connector#getProtocol()
     */
    @Override
    public String getProtocol()
    {
        return HTTP;
    }

    public String getProxyHostname()
    {
        return proxyHostname;
    }

    public String getProxyPassword()
    {
        return proxyPassword;
    }

    public int getProxyPort()
    {
        return proxyPort;
    }

    public String getProxyUsername()
    {
        return proxyUsername;
    }

    public void setProxyHostname(String host)
    {
        proxyHostname = host;
    }

    public void setProxyPassword(String string)
    {
        proxyPassword = string;
    }

    public void setProxyPort(int port)
    {
        proxyPort = port;
    }

    public void setProxyUsername(String string)
    {
        proxyUsername = string;
    }

    @Override
    public Map getReceivers()
    {
        return this.receivers;
    }

    public String getCookieSpec()
    {
        return cookieSpec;
    }

    public void setCookieSpec(String cookieSpec)
    {
        if (!(COOKIE_SPEC_NETSCAPE.equalsIgnoreCase(cookieSpec) || COOKIE_SPEC_RFC2109.equalsIgnoreCase(cookieSpec)))
        {
            throw new IllegalArgumentException(
                    CoreMessages.propertyHasInvalidValue("cookieSpec", cookieSpec).toString());
        }
        this.cookieSpec = cookieSpec;
    }

    public boolean isEnableCookies()
    {
        return enableCookies;
    }

    public void setEnableCookies(boolean enableCookies)
    {
        this.enableCookies = enableCookies;
    }


    public HttpClientConnectionManager getClientConnectionManager()
    {
        return clientConnectionManager;
    }

    public void setClientConnectionManager(HttpClientConnectionManager clientConnectionManager)
    {
        this.clientConnectionManager = clientConnectionManager;
    }

    protected HttpClient doClientConnect() throws Exception
    {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();

        if (getProxyUsername() != null)
        {

            if (isProxyNtlmAuthentication())
            {
                credsProvider.setCredentials(new AuthScope(getProxyHostname(), getProxyPort()),
                    new NTCredentials(getProxyUsername() + "/" + getProxyPassword()));
            }
            else
            {
                credsProvider.setCredentials(new AuthScope(getProxyHostname(), getProxyPort()),
                    new UsernamePasswordCredentials(getProxyUsername(), getProxyPassword()));
            }
        }

        Registry<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider> create()
            .register(AuthSchemes.NTLM, new JCIFSNTLMSchemeFactory())
            .register(AuthSchemes.BASIC, new BasicSchemeFactory())
            .register(AuthSchemes.DIGEST, new DigestSchemeFactory())
            .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
            .register(AuthSchemes.KERBEROS, new KerberosSchemeFactory())
            .build();

        HttpClient client = HttpClients.custom()
            .setDefaultCredentialsProvider(credsProvider)
            .setConnectionManager(clientConnectionManager)
            .setDefaultAuthSchemeRegistry(authSchemeRegistry)
            .setUserTokenHandler(new UserTokenHandler() {
                @Override
                public Object getUserToken(HttpContext context) {
                    return null;
                }
            })
            .build();

        return client;
    }

    protected void setupClientAuthorization(MuleEvent event, org.apache.http.HttpRequest httpMethod,
                                            HttpClient client, ImmutableEndpoint endpoint)
            throws UnsupportedEncodingException
    {
        // TODO(pablo.kraan): HTTPCLIENT - fix this
        // httpMethod.setDoAuthentication(true);
        // client.getParams().setAuthenticationPreemptive(true);
        //
        // if (event != null && event.getCredentials() != null)
        // {
        // MuleMessage msg = event.getMessage();
        // String authScopeHost = msg.getOutboundProperty(HTTP_PREFIX + "auth.scope.host",
        // event.getMessageSourceURI().getHost());
        // int authScopePort = msg.getOutboundProperty(HTTP_PREFIX + "auth.scope.port",
        // event.getMessageSourceURI().getPort());
        // String authScopeRealm = msg.getOutboundProperty(HTTP_PREFIX + "auth.scope.realm",
        // AuthScope.ANY_REALM);
        // String authScopeScheme = msg.getOutboundProperty(HTTP_PREFIX + "auth.scope.scheme",
        // AuthScope.ANY_SCHEME);
        // client.getState().setCredentials(
        // new AuthScope(authScopeHost, authScopePort, authScopeRealm, authScopeScheme),
        // new UsernamePasswordCredentials(event.getCredentials().getUsername(), new String(
        // event.getCredentials().getPassword())));
        // }
        // else if (endpoint.getEndpointURI().getUserInfo() != null
        // && endpoint.getProperty(HttpConstants.HEADER_AUTHORIZATION) == null)
        // {
        // // Add User Creds
        // StringBuilder header = new StringBuilder(128);
        // header.append("Basic ");
        // header.append(new String(Base64.encodeBase64(endpoint.getEndpointURI().getUserInfo().getBytes(
        // endpoint.getEncoding()))));
        // httpMethod.addRequestHeader(HttpConstants.HEADER_AUTHORIZATION, header.toString());
        // }
        // //TODO MULE-4501 this sohuld be removed and handled only in the ObjectToHttpRequest transformer
        // else if (event != null &&
        // event.getMessage().getOutboundProperty(HttpConstants.HEADER_AUTHORIZATION) != null &&
        // httpMethod.getRequestHeader(HttpConstants.HEADER_AUTHORIZATION) == null)
        // {
        // String auth = event.getMessage().getOutboundProperty(HttpConstants.HEADER_AUTHORIZATION);
        // httpMethod.addRequestHeader(HttpConstants.HEADER_AUTHORIZATION, auth);
        // }
        // else
        // {
        // // don't use preemptive if there are no credentials to send
        // client.getParams().setAuthenticationPreemptive(false);
        // }
    }

    /**
     * Ensures that the supplied URL starts with a '/'.
     */
    public static String normalizeUrl(String url)
    {
        if (url == null)
        {
            url = "/";
        }
        else if (!url.startsWith("/"))
        {
            url = "/" + url;
        }
        return url;
    }

    public boolean isProxyNtlmAuthentication()
    {
        return proxyNtlmAuthentication;
    }

    public void setProxyNtlmAuthentication(boolean proxyNtlmAuthentication)
    {
        this.proxyNtlmAuthentication = proxyNtlmAuthentication;
    }

    public void connect(EndpointURI endpointURI)
    {
        connectionManager.addConnection(endpointURI);
    }

    public void disconnect(EndpointURI endpointURI)
    {
        connectionManager.removeConnection(endpointURI);
    }


    public HttpMessageReceiver lookupReceiver(Socket socket, RequestLine requestLine) throws NoReceiverForEndpointException
    {
        int port = ((InetSocketAddress) socket.getLocalSocketAddress()).getPort();
        String host = null;
        for (MessageReceiver messageReceiver : receivers.values())
        {
            if (messageReceiver.getEndpointURI().getPort() == port)
            {
                host = messageReceiver.getEndpointURI().getHost();
                break;
            }
        }
        if (host == null)
        {
            String url = requestLine.getUrlWithoutParams();
            throw new NoReceiverForEndpointException(HttpMessages.noReceiverFoundForUrl(url));
        }

        String requestUriWithoutParams = requestLine.getUrlWithoutParams();
        StringBuilder requestUri = new StringBuilder(80);
        if (requestUriWithoutParams.indexOf("://") == -1)
        {
            requestUri.append(getProtocol()).append("://").append(host).append(':').append(port);
            if (!ROOT_PATH.equals(requestUriWithoutParams))
            {
                requestUri.append(requestUriWithoutParams);
            }
        }

        String uriStr = requestUri.toString();
        // first check that there is a receiver on the root address
        if (logger.isTraceEnabled())
        {
            logger.trace("Looking up receiver on connector: " + getName() + " with URI key: "
                         + requestUri.toString());
        }

        HttpMessageReceiver receiver = (HttpMessageReceiver) lookupReceiver(uriStr);

        // If no receiver on the root and there is a request path, look up the
        // received based on the root plus request path
        if (receiver == null && !ROOT_PATH.equals(requestUriWithoutParams))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Secondary lookup of receiver on connector: " + getName()
                             + " with URI key: " + requestUri.toString());
            }

            receiver = (HttpMessageReceiver) findReceiverByStem(getReceivers(), uriStr);

            if (receiver == null && logger.isWarnEnabled())
            {
                logger.warn("No receiver found with secondary lookup on connector: " + getName()
                            + " with URI key: " + requestUri.toString());
                logger.warn("Receivers on connector are: "
                            + MapUtils.toString(getReceivers(), true));
            }
        }
        if (receiver == null)
        {
            throw new NoReceiverForEndpointException(HttpMessages.noReceiverFoundForUrl(requestUriWithoutParams));
        }
        return receiver;
    }

    //Leave for backward compatibility
    @Deprecated
    public HttpMessageReceiver lookupReceiver(Socket socket, HttpRequest request)
    {
        try
        {
            return this.lookupReceiver(socket, request.getRequestLine());
        }
        catch (NoReceiverForEndpointException e)
        {
            logger.debug("No receiver found: " + e.getMessage());
            return null;
        }
    }

    public static MessageReceiver findReceiverByStem(Map<Object, MessageReceiver> receivers, String uriStr)
    {
        int match = 0;
        MessageReceiver receiver = null;
        for (Map.Entry<Object, MessageReceiver> e : receivers.entrySet())
        {
            String key = (String) e.getKey();
            MessageReceiver candidate = e.getValue();
            if (uriStr.startsWith(key) && match < key.length())
            {
                match = key.length();
                receiver = candidate;
            }
        }
        return receiver;
    }

    @Override
    protected ServerSocket getServerSocket(URI uri) throws IOException
    {
        return super.getServerSocket(uri);
    }

    /**
     * @deprecated Use keepAlive property in the outbound endpoint.
     */
    @Override
    @Deprecated
    public boolean isKeepSendSocketOpen()
    {
        return super.isKeepSendSocketOpen();
    }

    /**
     * @deprecated Use keepAlive property in the outbound endpoint.
     */
    @Override
    @Deprecated
    public void setKeepSendSocketOpen(boolean keepSendSocketOpen)
    {
        logger.warn("keepSendSocketOpen attribute is deprecated, use keepAlive in the outbound endpoint instead");
        super.setKeepSendSocketOpen(keepSendSocketOpen);
    }

    public boolean isStaleConnectionCheckEnabled()
    {
        return staleConnectionCheckEnabled;
    }

    public void setStaleConnectionCheckEnabled(boolean staleConnectionCheckEnabled)
    {
        this.staleConnectionCheckEnabled = staleConnectionCheckEnabled;
    }
}
