/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.config.spring;

import org.mule.tck.junit4.FunctionalTestCase;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.math.RandomUtils;
import org.junit.Test;

/**
 * Tests the workaround for <a href="https://jira.spring.io/browse/SPR-12970"> Spring Issue SPR-12970</a>
 */
public class BeanFactoryConcurrentTestCase extends FunctionalTestCase
{

    private static final int K_ITERATIONS = 100;
    private static final int K_THREADS = 100;

    @Override
    protected String getConfigFile()
    {
        return "org/mule/test/integration/spring/simple-config.xml";
    }

    private volatile Exception lastException;

    @Test
    public void concurrentBeanFactoryAccess() throws Exception
    {
        List<Thread> threads = new ArrayList<>(K_THREADS);
        for (int i = 0; i < 100; ++i)
        {
            threads.add(new Thread()
            {
                @Override
                public void run()
                {
                    for (int i = 0; i < K_ITERATIONS; ++i)
                    {
                        try
                        {
                            String key = "" + i + "_" + RandomUtils.nextLong();
                            muleContext.getRegistry().registerObject(key, this);
                            muleContext.getRegistry().lookupObjects(this.getClass()).isEmpty();
                            muleContext.getRegistry().unregisterObject(key);
                        }
                        catch (Exception e)
                        {
                            lastException = e;
                        }
                    }
                }
            });
        }

        for (Thread thread : threads)
        {
            thread.start();
        }

        for (Thread thread : threads)
        {
            thread.join();
        }

        if (lastException != null)
        {
            throw lastException;
        }
    }
}
