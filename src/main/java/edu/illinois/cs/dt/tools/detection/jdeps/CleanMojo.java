/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.cs.dt.tools.detection.jdeps;

import edu.illinois.cs.dt.tools.detection.FileUtil;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugin.surefire.SurefirePlugin;
import org.apache.maven.plugin.surefire.AbstractSurefireMojo;

import java.io.File;

/**
 * Removes STARTS plugin artifacts.
 */
@Mojo(name = "clean", requiresDirectInvocation = true)
public class CleanMojo extends BaseMojo {
//    public void execute() throws MojoExecutionException {
//        File directory = new File(getArtifactsDir());
//        if (directory.exists()) {
//            FileUtil.delete(directory);
//        }
//    }
}
