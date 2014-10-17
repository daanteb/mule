/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.http.util;

import org.apache.http.conn.HttpClientConnectionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A utility class for periodically closing idle connections.
 */
public class IdleConnectionTimeoutThread extends Thread
{

    private List<HttpClientConnectionManager> connectionManagers = new ArrayList<HttpClientConnectionManager>();
    private boolean shutdown = false;
    private long timeoutInterval = 1000;
    private long connectionTimeout = 3000;

    public IdleConnectionTimeoutThread()
    {
        setDaemon(true);
    }

    public IdleConnectionTimeoutThread(long timeoutInterval, long connectionTimeout)
    {
        this();
        this.timeoutInterval = timeoutInterval;
        this.connectionTimeout = connectionTimeout;
    }

    /**
     * Adds a connection manager to be handled by this class.
     */
    public synchronized void addConnectionManager(HttpClientConnectionManager connectionManager)
    {
        if (shutdown)
        {
            throw new IllegalStateException("IdleConnectionTimeoutThread has been shutdown");
        }
        this.connectionManagers.add(connectionManager);
    }

    /**
     * Removes the connection manager from this class. The idle connections from the connection manager will
     * no longer be automatically closed by this class.
     */
    public synchronized void removeConnectionManager(HttpClientConnectionManager connectionManager)
    {
        if (shutdown)
        {
            throw new IllegalStateException("IdleConnectionTimeoutThread has been shutdown");
        }
        this.connectionManagers.remove(connectionManager);
    }

    /**
     * Closes idle connections.
     */
    public synchronized void run()
    {
        while (!shutdown)
        {
            for (HttpClientConnectionManager connManager : connectionManagers)
            {
                connManager.closeIdleConnections(connectionTimeout, TimeUnit.MILLISECONDS);
            }

            try
            {
                this.wait(timeoutInterval);
            }
            catch (InterruptedException e)
            {
            }
        }
        // clear out the connection managers now that we're shutdown
        this.connectionManagers.clear();
    }

    /**
     * Stops the thread used to close idle connections. This class cannot be used once shutdown.
     */
    public synchronized void shutdown()
    {
        this.shutdown = true;
        this.notifyAll();
    }

}
