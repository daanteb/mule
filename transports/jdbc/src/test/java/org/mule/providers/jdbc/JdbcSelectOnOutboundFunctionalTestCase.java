/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.providers.jdbc;

import org.mule.extras.client.MuleClient;
import org.mule.impl.MuleMessage;
import org.mule.providers.NullPayload;
import org.mule.tck.FunctionalTestCase;
import org.mule.umo.UMOMessage;
import org.mule.util.MuleDerbyTestUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.dbutils.QueryRunner;

public class JdbcSelectOnOutboundFunctionalTestCase extends FunctionalTestCase
{

    protected String getConfigResources()
    {
        return "jdbc-select-outbound.xml";
    }

    // @Override
    protected void doSetUp() throws Exception
    {
        super.doSetUp();

        JdbcConnector jdbcConnector = (JdbcConnector) managementContext.getRegistry().lookupConnector("jdbcConnector");
        QueryRunner qr = jdbcConnector.createQueryRunner();
        int updated;

        try
        {
            updated = qr.update(jdbcConnector.getConnection(), "DELETE FROM TEST");
            logger.debug(updated + " rows deleted");
        }
        catch (Exception e)
        {
            qr.update(jdbcConnector.getConnection(), "CREATE TABLE TEST(ID INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 0)  NOT NULL PRIMARY KEY,TYPE INTEGER,DATA VARCHAR(255),ACK TIMESTAMP,RESULT VARCHAR(255))");
            logger.debug("Table created");
        }

        updated = qr.update(jdbcConnector.getConnection(), "INSERT INTO TEST(TYPE, DATA) VALUES (1, 'Test')");
        logger.debug(updated + " rows updated");
    }

    protected void doFunctionalTearDown() throws Exception
    {
        JdbcConnector jdbcConnector = (JdbcConnector) managementContext.getRegistry().lookupConnector("jdbcConnector");
        QueryRunner qr = jdbcConnector.createQueryRunner();
        int updated = qr.update(jdbcConnector.getConnection(), "DELETE FROM TEST");
        logger.debug(updated + " rows deleted");

        super.doTearDown();
    }

    protected void suitePreSetUp() throws Exception
    {
        InputStream propertiesStream = this.getClass().getClassLoader().getResourceAsStream("derby.properties");
        MuleDerbyTestUtils.defaultDerbyCleanAndInit(propertiesStream, "database.name");
        super.suitePreSetUp();
    }

    public void testSelectOnOutbound() throws Exception
    {
        MuleClient client = new MuleClient();
        
        UMOMessage reply = client.send("vm://jdbc.test", new MuleMessage(NullPayload.getInstance()));
        assertNotNull(reply.getPayload());
        assertTrue(reply.getPayload() instanceof ArrayList);
        ArrayList resultList = (ArrayList) reply.getPayload(); 
        assertTrue(resultList.size() == 1);
        assertTrue(resultList.get(0) instanceof HashMap);
        HashMap resultMap = (HashMap) resultList.get(0);
        assertEquals(new Integer(1), resultMap.get("TYPE"));
        assertEquals("Test", resultMap.get("DATA"));
    }

}


