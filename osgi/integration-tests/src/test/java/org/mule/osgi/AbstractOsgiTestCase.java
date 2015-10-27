/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.osgi;

import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.configureConsole;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;

import java.io.File;
import java.net.URISyntaxException;

import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.karaf.options.LogLevelOption;
import org.ops4j.pax.exam.options.MavenArtifactUrlReference;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public abstract class AbstractOsgiTestCase
{

    @Configuration
    public Option[] config() throws URISyntaxException
    {
        return new Option[] {
                //TODO(pablo.kraan): OSGi - add some system property to enable debugging without needing re-build
                //KarafDistributionOption.debugConfiguration("5005", true),
                karafDistributionConfiguration()
                        .frameworkUrl(getDistributionArtifactUrl())
                        .unpackDirectory(new File("target", "exam"))
                        .useDeployFolder(false),

                keepRuntimeFolder(),
                //systemProperty("pax.exam.osgi.unresolved.fail").value("true"),
                logLevel(LogLevelOption.LogLevel.INFO),
                configureConsole().ignoreLocalConsole(),

                mavenBundle()
                        .groupId("org.mule.osgi")
                        .artifactId("mule-osgi-sample-app")
                        .versionAsInProject(),

                //vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"),
                //debugConfiguration("5005", true),
                // KarafDistributionOption.debugConfiguration("5005", true),
        };
    }

    private MavenArtifactUrlReference getDistributionArtifactUrl()
    {
        return maven()
                .groupId("org.mule.osgi")
                .artifactId("mule-osgi-standalone")
                .versionAsInProject()
                .type("zip");
    }
}
