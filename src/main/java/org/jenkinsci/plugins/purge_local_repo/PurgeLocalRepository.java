package org.jenkinsci.plugins.purge_local_repo;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Project;
import hudson.model.Descriptor.FormException;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Maven;
import hudson.tasks.Maven.ProjectWithMaven;
import hudson.util.FormValidation;
import hudson.util.TimeUnit2;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class PurgeLocalRepository extends BuildWrapper {

    private List<String> groupIds;
    private Integer numberOfBuilds;
    private Integer purgeInterval;
    private TimeUnit2 purgeIntervalUnit;
    
    private static final ThreadLocal<DateFormat> DF = new ThreadLocal<DateFormat>() {

        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        }
    };
    
    @Extension
    public static final DescriptorImpl descriptor = new DescriptorImpl();
    
    @DataBoundConstructor
    public PurgeLocalRepository(String groupIds, Integer numberOfBuilds, Integer numberOfDays) {
        if (numberOfBuilds != null && numberOfBuilds <= 0) {
            throw new IllegalArgumentException("number of builds must be > 0");
        }
        
        if (numberOfDays != null && numberOfDays <= 0) {
            throw new IllegalArgumentException("number of days must be > 0");
        }
        
        String grpIds = Util.fixEmptyAndTrim(groupIds);
        this.groupIds = grpIds != null ? Arrays.asList(grpIds.split(",")) : Collections.<String>emptyList();
        this.numberOfBuilds = numberOfBuilds;
        this.purgeInterval = numberOfDays;
        this.purgeIntervalUnit = TimeUnit2.DAYS;
    }
    
    public String getGroupIds() {
        return listToCsv(this.groupIds);
    }
    
    private static String listToCsv(List<String> list) {
        return Util.join(list, ",");
    }
    
    private List<String> getActualGroupIds() {
        return this.groupIds.isEmpty() ? descriptor.getGroupIdsAsList() : this.groupIds;
    }
    
    public Integer getNumberOfBuilds() {
        return this.numberOfBuilds;
    }

    private Integer getActualNumberOfBuilds() {
        return this.numberOfBuilds != null ? this.numberOfBuilds : descriptor.getNumberOfBuilds();
    }
    
    public Integer getNumberOfDays() {
        return Integer.valueOf((int)TimeUnit2.DAYS.convert(this.purgeInterval, this.purgeIntervalUnit));
    }
    
    private Integer getActualNumberOfDays() {
        return this.purgeInterval != null ? this.purgeInterval : descriptor.getNumberOfDays();
    }
    
    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        
        boolean usesDefaultRepository = false;
        boolean usesPrivateRepository = false;
        //TODO: handle case if repo is specified via alternate settings or -Dmaven.local.repo// try using the local repository specified on the command line...
        // String localRepository = System.getProperty( MavenSettingsBuilder.ALT_LOCAL_REPOSITORY_LOCATION ); 
        
        if (build instanceof MavenModuleSetBuild) {
            MavenModuleSetBuild m2Build = (MavenModuleSetBuild) build;
            
            if(m2Build.getParent().usesPrivateRepository()) {
                usesPrivateRepository = true;
            } else {
                usesDefaultRepository = true;
            }
        } else {
            AbstractProject aProject = build.getProject();
            if (aProject instanceof Project) {
                Project project = (Project) aProject;
                
                List<Maven> allMaven = project.getBuildersList().getAll(Maven.class);
                for (Maven maven : allMaven) {
                    if (maven.usesPrivateRepository()) {
                        usesPrivateRepository = true;
                    } else {
                        usesDefaultRepository = true;
                    }
                }
            }
        }
        
        if (usesPrivateRepository) {
            FilePath repo = build.getWorkspace().child(".repository");
            purgeRepository(repo, build, listener.getLogger());
        }
        
        // note that freestyle projects could theoretically use private AND default repos. Therefore no 'else if' here!
        if (usesDefaultRepository) {
            // see DefaultMavenSettingsBuilder
            String userHome = System.getProperty( "user.home" );
            File mavenUserConfigurationDirectory = new File( userHome, ".m2" );
            File localRepository = new File( mavenUserConfigurationDirectory, "repository"); 
                    
            FilePath repo = new FilePath(localRepository);
            purgeRepository(repo, build, listener.getLogger());
        }
        
        return new Environment() {
        };
    }

    
    private void purgeRepository(FilePath repoRoot, AbstractBuild build, PrintStream logger) throws IOException, InterruptedException {
        
        boolean needsPurge = false;
        
        FilePath lastPurgeFile = build.getWorkspace().child(".purgeLocalRepositoryPlugin");
        Properties props = new Properties();
        if (lastPurgeFile.exists()) {
            props.load(lastPurgeFile.read());
            
            if (getActualNumberOfBuilds() != null) {
                String property = props.getProperty("purge.lastBuildNumber");
                int lastBuildNumber = Integer.parseInt(property);
                
                if (build.getNumber() - lastBuildNumber >= getActualNumberOfBuilds() ) {
                    needsPurge = true;
                }
            }
            
            if (getActualNumberOfDays() != null) {
                String property = props.getProperty("purge.lastDate");
                try {
                    Date lastDate = DF.get().parse(property);
                    Date currentDate = new Date();
                    
                    double diffInDays = differenceInDays(currentDate, lastDate); 
                    if (diffInDays >= getActualNumberOfDays()) {
                        needsPurge = true;
                    }
                    
                } catch (ParseException e) {
                    throw new IOException(e);
                }
            }
        } else {
            needsPurge = true;
        }
        
        if (needsPurge) {
            if (getActualGroupIds().isEmpty()) {
                logger.println("Purging whole repository at " + repoRoot); 
                repoRoot.deleteContents();
            } else {
                for (String groupId : getActualGroupIds()) {
                    String[] pathElements = groupId.split("\\.");
                    FilePath groupIdRoot = repoRoot;
                    for (String element : pathElements) {
                        groupIdRoot = groupIdRoot.child(element);
                    }
                    logger.println("Purging: " + groupIdRoot);
                    groupIdRoot.deleteContents();
                }
            }
            
            
            // record last purge
            props.setProperty("purge.lastBuildNumber", "" + build.getNumber());
            props.setProperty("purge.lastDate", DF.get().format(new Date()));
            props.store(lastPurgeFile.write(), "settings of the purge-local-repo-plugin");
        }
    }

    private double differenceInDays(Date currentDate, Date lastDate) {
        // this may not be perfectly 'correct' regarding DST, but good enough for this case
        
        return (currentDate.getTime() - lastDate.getTime()) / (double)(24L*60*60*1000);
    }

    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        private List<String> groupIds;
        private Integer numberOfBuilds;
        private Integer purgeInterval;
        private TimeUnit2 purgeIntervalUnit;
        
        public DescriptorImpl() {
            super(PurgeLocalRepository.class);
            load();
        }
        
        @Override
        public String getDisplayName() {
            return "Purge local Maven repository before build";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return (item instanceof MavenModuleSet)
                || (item instanceof ProjectWithMaven);
        }
        
        @Override
        public boolean configure( StaplerRequest req, JSONObject json ) throws FormException {
            
            String grpIds = Util.fixEmptyAndTrim( json.getString("groupIds"));
            this.groupIds = grpIds != null ? Arrays.asList(grpIds.split(",")) : Collections.<String>emptyList();
            
            String nrOfBuilds = Util.fixEmpty(json.getString("numberOfBuilds"));
            try {
                this.numberOfBuilds = nrOfBuilds != null ? Integer.valueOf(nrOfBuilds) : null;
            } catch (NumberFormatException e) {
                throw new FormException(nrOfBuilds + " is no valid number", "numberOfBuilds");
            }
            
            String nrOfDays = Util.fixEmpty(json.getString("numberOfDays"));
            try {
                this.purgeInterval = nrOfDays != null ? Integer.valueOf(nrOfDays) : null;
            } catch (NumberFormatException e) {
                throw new FormException(nrOfDays + " is no valid number", "numberOfDays");
            }
            this.purgeIntervalUnit = TimeUnit2.DAYS;
            
            save();
            return true;
        }
        
        public String getGroupIds() {
            return listToCsv(this.groupIds);
        }
        
        public List<String> getGroupIdsAsList() {
            return this.groupIds;
        }
        
        public Integer getNumberOfBuilds() {
            return numberOfBuilds;
        }

        public Integer getNumberOfDays() {
            return Integer.valueOf((int)TimeUnit2.DAYS.convert(this.purgeInterval, this.purgeIntervalUnit));
        }
        
        public FormValidation doCheckNumberOfBuilds(@QueryParameter String numberOfBuilds) {
            return checkIsGreaterZero(numberOfBuilds);
        }

        public FormValidation doCheckNumberOfDays(@QueryParameter String numberOfDays) {
            return checkIsGreaterZero(numberOfDays);
        }

        private FormValidation checkIsGreaterZero(String value) {
            if (value == null || value.trim().length() == 0) {
                return FormValidation.ok();
            }
            try {
                int nr = Integer.parseInt(value);
                if (nr <= 0) {
                    return FormValidation.error("Must be > 0!");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error(value + " is no valid number");
            }
            
            return FormValidation.ok();
        }
    }
}
