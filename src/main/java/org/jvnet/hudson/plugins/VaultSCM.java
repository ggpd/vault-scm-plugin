/**
 * @author Antti Relander / Eficode
 * @verison 0.1
 * @since 2011-12-07
 */
package org.jvnet.hudson.plugins;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.Secret;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class VaultSCM extends SCM {

    private static final Logger LOG = Logger.getLogger(VaultSCM.class.getName());

    public static final class VaultSCMDescriptor extends SCMDescriptor<VaultSCM> {

        private final static List<String> MERGE_OPTIONS = Arrays.asList("automatic", "overwrite", "later");
        private final static List<String> FILETIME_OPTIONS = Arrays.asList("checkin", "current", "modification");

        /**
         * Constructor for a new VaultSCMDescriptor.
         */
        protected VaultSCMDescriptor() {
            super(VaultSCM.class, null);
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return true;
        }

        public VaultSCMInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(VaultSCMInstallation.DescriptorImpl.class);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "SourceGear Vault";
        }

        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            return super.newInstance(req, formData);
        }

        public List<String> getMergeOptions() {
            return MERGE_OPTIONS;
        }

        public List<String> getFileTimeOptions() {
            return FILETIME_OPTIONS;
        }

        @Override
        public boolean isApplicable(Job project) {
            return true;
        }

        public FormValidation doCheckServerName(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckVaultExecutable(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckRepositoryName(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckPath(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateRequired(value);
        }
    }

    private static final Semaphore sem = new Semaphore(1);
    
    //configuration variables from user interface
    private String serverName;
    private String userName;
    private Secret password;
    private String repositoryName; //name of the repository
    private String vaultName; // The name of the vault installation from global config
    private String path; //path in repository. Starts with $ sign.
    private boolean sslEnabled; //ssl enabled?
    private boolean useNonWorkingFolder;
    private String merge;
    private String fileTime;
    private boolean makeWritableEnabled;
    private boolean verboseEnabled;

    public boolean getMakeWritableEnabled() {
        return makeWritableEnabled;
    }

    @DataBoundSetter
    public void setMakeWritableEnabled(boolean makeWritableEnabled) {
        this.makeWritableEnabled = makeWritableEnabled;
    }

    public boolean getSslEnabled() {
        return sslEnabled;
    }

    @DataBoundSetter
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public boolean getVerboseEnabled() {
        return verboseEnabled;
    }

    @DataBoundSetter
    public void setVerboseEnabled(boolean verboseEnabled) {
        this.verboseEnabled = verboseEnabled;
    }

    public boolean getUseNonWorkingFolder() {
        return useNonWorkingFolder;
    }

    @DataBoundSetter
    public void setUseNonWorkingFolder(boolean useNonWorkingFolder) {
        this.useNonWorkingFolder = useNonWorkingFolder;
    }

    public String getMerge() {
        return merge;
    }

    @DataBoundSetter
    public void setMerge(String merge) {
        this.merge = merge;
    }

    public String getFileTime() {
        return fileTime;
    }

    @DataBoundSetter
    public void setFileTime(String fileTime) {
        this.fileTime = fileTime;
    }

    public String getVaultName() {
        return vaultName;
    }

    @DataBoundSetter
    public void setVaultName(String vaultName) {
        this.vaultName = vaultName;
    }

    public String getPath() {
        return path;
    }

    @DataBoundSetter
    public void setPath(String path) {
        this.path = path;
    }

    public String getServerName() {
        return serverName;
    }

    @DataBoundSetter
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getUserName() {
        return userName;
    }

    @DataBoundSetter
    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return Secret.toString(password);
    }

    @DataBoundSetter
    public void setPassword(String password) {
        this.password = Secret.fromString(password);
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    @DataBoundSetter
    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public VaultSCMInstallation getVault() {
        for (VaultSCMInstallation i : DESCRIPTOR.getToolDescriptor().getInstallations()) {
            if (vaultName != null && i.getName().equals(vaultName)) {
                return i;
            }
        }
        return null;
    }
    /**
     * Singleton descriptor.
     */
    @Extension
    public static final VaultSCMDescriptor DESCRIPTOR = new VaultSCMDescriptor();

    public static final String VAULT_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
    private SimpleDateFormat dateFormatter = new SimpleDateFormat(VAULT_DATE_FORMAT);

    @DataBoundConstructor
    public VaultSCM(String serverName, String path, String userName,
            String password, String repositoryName, String vaultName,
            boolean sslEnabled, boolean useNonWorkingFolder, String merge,
            String fileTime, boolean makeWritableEnabled,
            boolean verboseEnabled) {
        this.serverName = serverName;
        this.userName = userName;
        this.password = Secret.fromString(password);
        this.repositoryName = repositoryName;
        this.vaultName = vaultName;
        this.path = path;
        this.sslEnabled = sslEnabled; //Default to true
        this.useNonWorkingFolder = useNonWorkingFolder;
        this.merge = (merge == null || merge.isEmpty()) ? "overwrite" : merge;
        this.fileTime = (fileTime == null || fileTime.isEmpty()) ? "modification" : fileTime;
        this.makeWritableEnabled = makeWritableEnabled;
        this.verboseEnabled = verboseEnabled;
    }

    @Override
    public SCMDescriptor<?> getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SCMRevisionState calcRevisionsFromBuild(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException,
            InterruptedException {

        VaultSCMRevisionState scmRevisionState = new VaultSCMRevisionState();
        final Date lastBuildDate = build.getTime();
        scmRevisionState.setDate(lastBuildDate);

        return scmRevisionState;
    }

    @Override
    public PollingResult compareRemoteRevisionWith(Job<?,?> project, Launcher launcher, FilePath workspace, TaskListener listener, SCMRevisionState baseline) 
        throws IOException, InterruptedException {

        Date lastBuild = ((VaultSCMRevisionState) baseline).getDate();
        LOG.log(Level.INFO, "Last Build Date set to {0}", lastBuild.toString());
        Date now = new Date();
        File temporaryFile = File.createTempFile("changes", ".txt");
        int countChanges = determineChangeCount(launcher, workspace, listener, lastBuild, now, temporaryFile);
        boolean bDeleted = temporaryFile.delete();
        if(!bDeleted)
        {
            throw new IOException();
        }

        if (countChanges == 0) {
            return PollingResult.NO_CHANGES;
        } else {
			return PollingResult.BUILD_NOW;
		}
    }

    private boolean checkVaultPath(String path, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        FilePath exec = new FilePath(launcher.getChannel(), path);
        try {
            if (!exec.exists()) {
                return false;
            }
        } catch (IOException e) {
            listener.fatalError("Failed checking for existence of " + path);
            return false;
        }
        return true;
    }

    private String getVaultPath(Launcher launcher, TaskListener listener) throws InterruptedException, IOException {

        final String defaultPath = "C:\\Program Files\\SourceGear\\Vault Client\\vault.exe";
        final String defaultPathX86 = "C:\\Program Files (x86)\\SourceGear\\Vault Client\\vault.exe";

        VaultSCMInstallation installation = getVault();
        String pathToVault;

        if (installation == null) {
            // Check the first default location for vault...
            if (checkVaultPath(defaultPath, launcher, listener)) {
                pathToVault = defaultPath;
            } else if (checkVaultPath(defaultPathX86, launcher, listener)) {
                pathToVault = defaultPathX86;
            } else {
                listener.fatalError("Failed find vault client");
                return null;
            }
        } else {
            Node node = Computer.currentComputer().getNode();
            if(node == null) {
                return null;
            }

            VaultSCMInstallation ins = installation.forNode(node, listener);
            pathToVault = ins.getVaultLocation();
            if (!checkVaultPath(pathToVault, launcher, listener)) {
                listener.fatalError(pathToVault + " doesn't exist");
                return null;
            }
        }
        return pathToVault;
    }

    @Override
    public String getKey(){
        return this.vaultName;
    }
    
    @Override
    public boolean supportsPolling(){
        return true;
    }

    @Override
    public void checkout(Run<?,?> build, Launcher launcher, FilePath workspace, TaskListener listener, File changelogFile, SCMRevisionState baseline) throws IOException, InterruptedException {
        String pathToVault = getVaultPath(launcher, listener);

        if(pathToVault == null || pathToVault.isEmpty()){
            throw new AbortException("Failed to find Vault path.");
        }

        //populate the GET command
        //in some cases username, host and password can be empty e.g. if rememberlogin is used to store login data
        ArgumentListBuilder argBuildr = new ArgumentListBuilder();

        argBuildr.add(pathToVault);
        argBuildr.add("GET");

        if (serverName != null && !serverName.isEmpty()) {
            argBuildr.add("-host", serverName);
        }

        if (!userName.isEmpty()) {
            argBuildr.add("-user", userName);
        }

        if (!Secret.toString(password).isEmpty()) {
            argBuildr.add("-password");
            argBuildr.add(Secret.toString(password), true);
        }

        if (!repositoryName.isEmpty()) {
            argBuildr.add("-repository", repositoryName);
        }

        if (this.sslEnabled) {
            argBuildr.add("-ssl");
        }

        if (this.verboseEnabled) {
            argBuildr.add("-verbose");
        }

        if (this.makeWritableEnabled) {
            argBuildr.add("-makewritable");
        }

        argBuildr.add("-merge", merge);

        argBuildr.add("-setfiletime", fileTime);

        if (this.useNonWorkingFolder) {
            argBuildr.add("-nonworkingfolder", workspace.getRemote());
        } else {
            argBuildr.add("-workingfolder", workspace.getRemote());
        }
        
        argBuildr.add(this.path);
        boolean semResult = sem.tryAcquire(5, TimeUnit.MINUTES);
        if(!semResult)
        {
            throw new AbortException("Failed to acquire semaphore.");
        }

        int cmdResult = launcher.launch().cmds(argBuildr).envs(build.getEnvironment(TaskListener.NULL)).stdout(listener.getLogger()).pwd(workspace).join();
        sem.release();
        if (cmdResult == 0) {
            final Run<?, ?> lastBuild = build.getPreviousBuild();
            final Date lastBuildDate;

            if (lastBuild == null) {
                lastBuildDate = new Date();
                lastBuildDate.setTime(0); // default to January 1, 1970
                listener.getLogger().print("Never been built.");
            } else {
                lastBuildDate = lastBuild.getTimestamp().getTime();
            }

            Date now = new Date(); //defaults to current

            if(changelogFile != null){
                captureChangeLog(launcher, workspace, listener, lastBuildDate, now, changelogFile);
            }
        } else {
            throw new AbortException("Failed to pull vault contents.");
        }

        listener.getLogger().println("Checkout completed.");
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new VaultSCMChangeLogParser();
    }

    private boolean captureChangeLog(Launcher launcher, FilePath workspace,
            TaskListener listener, Date lastBuildDate, Date currentDate, File changelogFile) throws IOException, InterruptedException {

        boolean result = true;

        String latestBuildDate = dateFormatter.format(lastBuildDate);

        String today = dateFormatter.format(currentDate);

        String pathToVault = getVaultPath(launcher, listener);

        if (pathToVault == null) {
            return false;
        }

        FileOutputStream os = new FileOutputStream(changelogFile);
        try {
            BufferedOutputStream bos = new BufferedOutputStream(os);
            try {

                ArgumentListBuilder argBuildr = new ArgumentListBuilder();
                argBuildr.add(pathToVault);
                argBuildr.add("VERSIONHISTORY");

                if (!serverName.isEmpty()) {
                    argBuildr.add("-host", serverName);
                }

                if (!userName.isEmpty()) {
                    argBuildr.add("-user", userName);
                }

                if (!Secret.toString(password).isEmpty()) {
                    argBuildr.add("-password");
                    argBuildr.add(Secret.toString(password), true);
                }

                if (!repositoryName.isEmpty()) {
                    argBuildr.add("-repository", repositoryName);
                }

                if (this.sslEnabled) {
                    argBuildr.add("-ssl");
                }

                argBuildr.add("-enddate", today);
                argBuildr.add("-begindate", latestBuildDate);
                argBuildr.add(this.path);

                boolean semResult = sem.tryAcquire(5, TimeUnit.MINUTES);
                if(!semResult)
                {
                    throw new AbortException("Failed to acquire semaphore.");
                }

                int cmdResult = launcher.launch().cmds(argBuildr).envs(new String[0]).stdout(bos).pwd(workspace).join();

                sem.release();
                if (cmdResult != 0) {
                    listener.fatalError("Changelog failed with exit code " + cmdResult);
                    result = false;
                }

            } finally {
                bos.close();
            }
        } finally {
            os.close();
        }

        listener.getLogger().println("Changelog calculated successfully.");
        listener.getLogger().println("Change log file: " + changelogFile.getAbsolutePath());

        return result;
    }

    private int determineChangeCount(Launcher launcher, FilePath workspace,
            TaskListener listener, Date lastBuildDate, Date currentDate, File changelogFile) throws IOException, InterruptedException {
                listener.getLogger().println("Determine change count.");
        int result = 0;
        String latestBuildDate = dateFormatter.format(lastBuildDate);
        String today = dateFormatter.format(currentDate);
        String pathToVault = getVaultPath(launcher, listener);

        if (pathToVault == null) {
            return 0;
        }

        FileOutputStream os = new FileOutputStream(changelogFile);
        try {
            BufferedOutputStream bos = new BufferedOutputStream(os);
            try {
                ArgumentListBuilder argBuildr = new ArgumentListBuilder();
                argBuildr.add(pathToVault);
                argBuildr.add("VERSIONHISTORY");

                if (!serverName.isEmpty()) {
                    argBuildr.add("-host", serverName);
                }

                if (!userName.isEmpty()) {
                    argBuildr.add("-user", userName);
                }

                if (!Secret.toString(password).isEmpty()) {
                    argBuildr.add("-password");
                    argBuildr.add(Secret.toString(password), true);
                }

                if (!repositoryName.isEmpty()) {
                    argBuildr.add("-repository", repositoryName);
                }

                if (this.sslEnabled) {
                    argBuildr.add("-ssl");
                }

                argBuildr.add("-enddate", today);
                argBuildr.add("-begindate", latestBuildDate);
                argBuildr.add(this.path);

                boolean semResult = sem.tryAcquire(5, TimeUnit.MINUTES);
                if(!semResult)
                {
                    return 0;
                }

                int cmdResult = launcher.launch().cmds(argBuildr).envs(new String[0]).stdout(bos).pwd(workspace).join();
                sem.release();
                if (cmdResult != 0) {
                    listener.fatalError("Determine changes count failed with exit code " + cmdResult);
                    result = 0;
                }

            } finally {
                bos.close();
            }
        } finally {
            os.close();
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(changelogFile);
            doc.getDocumentElement().normalize();
            NodeList nodeLst = doc.getElementsByTagName("item");
            result = nodeLst.getLength();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }
}
