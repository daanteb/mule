/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.http;

import org.apache.http.util.EntityUtils;
import org.mule.model.streaming.DelegatingInputStream;

import java.io.IOException;
import java.io.InputStream;

public class ReleasingInputStream extends DelegatingInputStream
{

    private final org.apache.http.HttpResponse method;

    public ReleasingInputStream(InputStream is, org.apache.http.HttpResponse method)
    {
        super(is);

        this.method = method;
    }

    public void close() throws IOException
    {
        super.close();

        if (method != null)
        {
            EntityUtils.consumeQuietly(method.getEntity());
        }
    }
}

