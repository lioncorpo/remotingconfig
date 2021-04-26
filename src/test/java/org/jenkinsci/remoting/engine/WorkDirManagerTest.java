/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) 2016-2017 CloudBees, Inc.
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in
 *  * all copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  * THE SOFTWARE.
 *
 */

package org.jenkinsci.remoting.engine;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import org.jenkinsci.remoting.engine.WorkDirManager.DirType;
import org.junit.After;
import org.junit.Before;
import org.jvnet.hudson.test.Bug;

/**
 * Tests of {@link WorkDirManager}
 * @author Oleg Nenashev.
 * @since TODO
 */
public class WorkDirManagerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();
    
    @Rule
    public WorkDirManagerRule mgr = new WorkDirManagerRule();

    @Test
    public void shouldInitializeCorrectlyForExistingDirectory() throws Exception {
        final File dir = tmpDir.newFolder("foo");

        // Probe files to confirm the directory does not get wiped
        final File probeFileInWorkDir = new File(dir, "probe.txt");
        FileUtils.write(probeFileInWorkDir, "Hello!");
        final File remotingDir = new File(dir, DirType.INTERNAL_DIR.getDefaultLocation());
        Files.createDirectory(remotingDir.toPath());
        final File probeFileInInternalDir = new File(remotingDir, "/probe.txt");
        FileUtils.write(probeFileInInternalDir, "Hello!");

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(dir, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        assertThat("The initialized " + DirType.INTERNAL_DIR + " differs from the expected one", createdDir.toFile(), equalTo(remotingDir));

        // Ensure that the files have not been wiped
        Assert.assertTrue("Probe file in the " + DirType.WORK_DIR + " has been wiped", probeFileInWorkDir.exists());
        Assert.assertTrue("Probe file in the " + DirType.INTERNAL_DIR + " has been wiped", probeFileInInternalDir.exists());
        
        // Ensure that sub directories are in place
        assertExists(DirType.JAR_CACHE_DIR);
        assertExists(DirType.LOGS_DIR);
    }

    @Test
    public void shouldPerformMkdirsIfRequired() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Assert.assertFalse("The " +  DirType.INTERNAL_DIR + " should not exist in the test", workDir.exists());

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, DirType.INTERNAL_DIR.getDefaultLocation());

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        assertThat("The initialized " + DirType.INTERNAL_DIR + " differs from the expected one", createdDir.toFile(), equalTo(remotingDir));
        Assert.assertTrue("Remoting " + DirType.INTERNAL_DIR +  " should have been initialized", remotingDir.exists());
    }

    @Test
    public void shouldProperlyCreateDirectoriesForCustomInternalDirs() throws Exception {
        final String internalDirectoryName = "myRemotingLogs";
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/another/path");
        Assert.assertFalse("The " + DirType.WORK_DIR + " should not exist in the test", workDir.exists());

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, internalDirectoryName);

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirectoryName, false);
        assertThat("The initialized " + DirType.INTERNAL_DIR + " differs from the expected one", createdDir.toFile(), equalTo(remotingDir));
        Assert.assertTrue("Remoting " + DirType.INTERNAL_DIR + " should have been initialized", remotingDir.exists());
    }

    @Test
    public void shouldFailIfWorkDirIsAFile() throws IOException {
        File foo = tmpDir.newFile("foo");
        try {
            WorkDirManager.getInstance().initializeWorkDir(foo, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        } catch (IOException ex) {
            assertThat("Wrong exception message",
                    ex.getMessage(), containsString("The specified " + DirType.WORK_DIR + " path points to a non-directory file"));
            return;
        }
        Assert.fail("The " + DirType.WORK_DIR + " has been initialized, but it should fail due to the conflicting file");
    }

    @Test
    public void shouldFailIfWorkDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(DirType.WORK_DIR, DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    public void shouldFailIfWorkDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(DirType.WORK_DIR, DirectoryFlag.NOT_WRITABLE);
    }


    @Test
    public void shouldFailIfWorkDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(DirType.WORK_DIR, DirectoryFlag.NOT_READABLE);
    }

    @Test
    public void shouldFailIfInternalDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(DirType.INTERNAL_DIR, DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    public void shouldFailIfInternalDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(DirType.INTERNAL_DIR, DirectoryFlag.NOT_WRITABLE);
    }


    @Test
    public void shouldFailIfInternalDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(DirType.INTERNAL_DIR, DirectoryFlag.NOT_READABLE);
    }

    @Test
    public void shouldNotSupportPathDelimitersAndSpacesInTheInternalDirName() throws IOException {
        File foo = tmpDir.newFolder("foo");

        assertAllocationFails(foo, " remoting ");
        assertAllocationFails(foo, " remoting");
        assertAllocationFails(foo, "directory with spaces");
        assertAllocationFails(foo, "nested/directory");
        assertAllocationFails(foo, "nested\\directory\\in\\Windows");
        assertAllocationFails(foo, "just&a&symbol&I&do&not&like");
    }

    @Test
    @Bug(39130)
    public void shouldFailToStartupIf_WorkDir_IsMissing_andRequired() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Assert.assertFalse("The " +  DirType.INTERNAL_DIR + " should not exist in the test", workDir.exists());

        assertAllocationFailsForMissingDir(workDir, DirType.WORK_DIR);
    }

    @Test
    @Bug(39130)
    public void shouldFailToStartupIf_InternalDir_IsMissing_andRequired() throws Exception {
        // Create only the working directory, not the nested one
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Files.createDirectories(workDir.toPath());

        assertAllocationFailsForMissingDir(workDir, DirType.INTERNAL_DIR);
    }
    
    @Test
    public void shouldNotCreateLogsDirIfDisabled() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        assertDoesNotCreateDisabledDir(tmpDirFile, DirType.LOGS_DIR);
    }
    
    @Test
    public void shouldNotCreateJarCacheIfDisabled() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        assertDoesNotCreateDisabledDir(tmpDirFile, DirType.JAR_CACHE_DIR);
    }

    @Test
    public void shouldCreateLogFilesOnTheDisk() throws Exception {
        final File workDir = tmpDir.newFolder("workDir");
        final WorkDirManager mngr = WorkDirManager.getInstance();
        mngr.initializeWorkDir(workDir, "remoting", false);
        mngr.setupLogging(mngr.getLocation(DirType.INTERNAL_DIR).toPath(), null);
        
        // Write something to logs
        String message = String.format("Just 4 test. My Work Dir is %s", workDir);
        Logger.getLogger(WorkDirManager.class.getName()).log(Level.INFO, message);
        
        // Ensure log files have been created
        File logsDir = mngr.getLocation(DirType.LOGS_DIR);
        assertFileLogsExist(logsDir, "remoting.log", 0);
        
        // Ensure the entry has been written
        File log0 = new File(logsDir, "remoting.log.0");
        try (FileInputStream istr = new FileInputStream(log0)) {
            String contents = IOUtils.toString(istr);
            assertThat("Log file " + log0 + " should contain the probe message", contents, containsString(message));
        }
    }
    
    @Test
    public void shouldUseLoggingSettingsFromFileDefinedByAPI() throws Exception {
        final File loggingConfigFile = new File(tmpDir.getRoot(), "julSettings.prop");
        doTestLoggingConfig(loggingConfigFile, true);
    }
    
    @Test
    public void shouldUseLoggingSettingsFromFileDefinedBySystemProperty() throws Exception {
        final File loggingConfigFile = new File(tmpDir.getRoot(), "julSettings.prop");
        final String oldValue = System.setProperty(WorkDirManager.JUL_CONFIG_FILE_SYSTEM_PROPERTY_NAME, loggingConfigFile.getAbsolutePath());
        try {
            doTestLoggingConfig(loggingConfigFile, false);
        } finally {
            // TODO: Null check and setting empty string is a weird hack
            System.setProperty(WorkDirManager.JUL_CONFIG_FILE_SYSTEM_PROPERTY_NAME, oldValue != null ? oldValue : "");
        }
    }
    
    private void doTestLoggingConfig(File loggingConfigFile, boolean passToManager) throws IOException, AssertionError{
        final File workDir = tmpDir.newFolder("workDir");
        final File customLogDir = tmpDir.newFolder("mylogs");
        
        // Create config file
        try (FileWriter wr = new FileWriter(loggingConfigFile)) {
            // Init FileHandler with default XML formatter
            wr.write("handlers= java.util.logging.FileHandler\n");
            // TODO: It won't work well on Windows
            wr.write("java.util.logging.FileHandler.pattern=" + customLogDir.getAbsolutePath() + "/mylog.log.%g\n");
            wr.write("java.util.logging.FileHandler.limit=81920\n");
            wr.write("java.util.logging.FileHandler.count=5\n");
        }
        
        // Init WorkDirManager
        final WorkDirManager mngr = WorkDirManager.getInstance();
        if (passToManager) {
            mngr.setLoggingConfig(loggingConfigFile);
        }
        mngr.initializeWorkDir(workDir, "remoting", false);
        mngr.setupLogging(mngr.getLocation(DirType.INTERNAL_DIR).toPath(), null);
        
        // Write something to logs
        String message = String.format("Just 4 test. My Work Dir is %s", workDir);
        Logger.getLogger(WorkDirManager.class.getName()).log(Level.INFO, message);
        
        // Assert that logs directory still exists, but has no default logs
        assertExists(DirType.LOGS_DIR);
        File defaultLog0 = new File(mngr.getLocation(DirType.LOGS_DIR), "remoting.log.0");
        Assert.assertFalse("Log settings have been passed from the config file, the default log should not exist: " + defaultLog0, 
                defaultLog0.exists());
        
        // Assert that logs have been written to the specified custom destination
        assertFileLogsExist(customLogDir, "mylog.log", 0);
        File log0 = new File(customLogDir, "mylog.log.0");
        try (FileInputStream istr = new FileInputStream(log0)) {
            String contents = IOUtils.toString(istr);
            assertThat("Log file " + log0 + " should contain the probe message", contents, containsString(message));
        }
    }
    
    private void assertFileLogsExist(File logsDir, String prefix, int logFilesNumber) {
        for (int i=0; i<logFilesNumber; ++i) {
            File log = new File(logsDir, prefix + "." + i);
            Assert.assertTrue("Log file should exist: " + log, log.exists());
        }
    }
    
    private void assertAllocationFailsForMissingDir(File workDir, DirType expectedCheckFailure) {
        // Initialize and check the results
        try {
            WorkDirManager.getInstance().initializeWorkDir(workDir, DirType.INTERNAL_DIR.getDefaultLocation(), true);
        } catch (IOException ex) {
            assertThat("Unexpected exception message", ex.getMessage(),
                    containsString("The " + expectedCheckFailure + " is missing, but it is expected to exist:"));
            return;
        }
        Assert.fail("The workspace allocation did not fail for the missing " + expectedCheckFailure);
    }

    private void assertAllocationFails(File workDir, String internalDirName) throws AssertionError {
        try {
            WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirName, false);
        }  catch (IOException ex) {
            assertThat(ex.getMessage(), containsString(String.format("Name of %s ('%s') is not compliant with the required format",
                    DirType.INTERNAL_DIR, internalDirName)));
            return;
        }
        Assert.fail("Initialization of WorkDirManager with invalid internal directory '" + internalDirName + "' should have failed");
    }
    
    private void assertExists(@Nonnull DirType type) throws AssertionError {
        final File location = WorkDirManager.getInstance().getLocation(type);
        Assert.assertNotNull("WorkDir Manager didn't provide location of " + type, location);
        Assert.assertTrue("Cannot find the " + type + " directory: " + location, location.exists());
    }
    
    private void assertDoesNotCreateDisabledDir(File workDir, DirType type) throws AssertionError, IOException {
        WorkDirManager instance = WorkDirManager.getInstance();
        instance.disable(type);
        instance.initializeWorkDir(workDir, "remoting", false);
        
        // Checks
        assertThat("Directory " + type + " has been added to the cache. Expected WirkDirManager to ignore it", 
                instance.getLocation(type), nullValue());
        File internalDir = instance.getLocation(DirType.INTERNAL_DIR);
        File expectedDir = new File(internalDir, type.getDefaultLocation());
        Assert.assertFalse("The logs directoy should not exist", expectedDir.exists());
    }

    private void verifyDirectoryFlag(DirType type, DirectoryFlag flag) throws IOException, AssertionError {
        final File dir = tmpDir.newFolder("test-" + type.getClass().getSimpleName() + "-" + flag);

        switch (type) {
            case WORK_DIR:
                flag.modifyFile(dir);
                break;
            case INTERNAL_DIR:
                // Then we create remoting dir and also modify it
                File remotingDir = new File(dir, DirType.INTERNAL_DIR.getDefaultLocation());
                remotingDir.mkdir();
                flag.modifyFile(remotingDir);
                break;
            default:
                Assert.fail("Unsupported Directory type: " + type);
        }

        try {
            WorkDirManager.getInstance().initializeWorkDir(dir, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        } catch (IOException ex) {
            assertThat("Wrong exception message for " + flag,
                    ex.getMessage(), containsString("The specified " + type + " should be fully accessible to the remoting executable"));
            return;
        }
        Assert.fail("The directory has been initialized, but it should fail since the target dir is " + flag);
    }

    private enum DirectoryFlag {
        NOT_WRITABLE,
        NOT_READABLE,
        NOT_EXECUTABLE;

        public void modifyFile(File file) throws AssertionError {
            switch (this) {
                case NOT_EXECUTABLE:
                    file.setExecutable(false);
                    break;
                case NOT_WRITABLE:
                    file.setWritable(false);
                    break;
                case NOT_READABLE:
                    file.setReadable(false);
                    break;
                default:
                    Assert.fail("Unsupported file mode " + this);
            }
        }
    }

}
