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
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Maven;
import hudson.tasks.Maven.ProjectWithMaven;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.maven.repository.RepositorySystem;
import org.kohsuke.stapler.DataBoundConstructor;

public class PurgeLocalRepository extends BuildWrapper {

    private List<String> groupIds;
    private Integer numberOfBuilds;
    private Integer numberOfDays;
    
    private static final ThreadLocal<DateFormat> DF = new ThreadLocal<DateFormat>() {

        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        }
    };
    
    @DataBoundConstructor
    public PurgeLocalRepository(String groupIds, Integer numberOfBuilds, Integer numberOfDays) {
        if (numberOfBuilds != null && numberOfBuilds <= 0) {
            throw new IllegalArgumentException("number of builds must be > 0");
        }
        
        if (numberOfDays != null && numberOfDays <= 0) {
            throw new IllegalArgumentException("number of days must be > 0");
        }
        
        String grpIds = Util.fixNull(groupIds);
        this.groupIds = Arrays.asList(grpIds.split(","));
        this.numberOfBuilds = numberOfBuilds;
        this.numberOfDays = numberOfDays;
    }
    
    public String getGroupIds() {
        StringBuilder buf = new StringBuilder();
        for(String groupId : this.groupIds) {
            buf.append(groupId).append(",");
        }
        if (buf.length() > 0) {
            buf.deleteCharAt(buf.length() - 1);
        }
        return buf.toString();
    }
    
    public Integer getNumberOfBuilds() {
        return this.numberOfBuilds;
    }
    
    public Integer getNumberOfDays() {
        return this.numberOfDays;
    }
    
    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        
        boolean usesDefaultRepository = false;
        boolean usesPrivateRepository = false;
        //TODO: handle case if repo is specified via alternate settings or -Dmaven.local.repo
        
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
            listener.getLogger().println("Cleaning private repository at " + repo);
            purgeRepository(repo, build, listener.getLogger());
        }
        
        // note that freestyle projects could theoretically use private AND default repos. Therefore no 'else if' here!
        if (usesDefaultRepository) {
            FilePath repo = new FilePath(RepositorySystem.defaultUserLocalRepository);
            listener.getLogger().println("Cleaning public repository at " + repo);
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
            
            if (this.numberOfBuilds != null) {
                String property = props.getProperty("purge.lastBuildNumber");
                int lastBuildNumber = Integer.parseInt(property);
                
                if (build.getNumber() - lastBuildNumber >= this.numberOfBuilds ) {
                    needsPurge = true;
                }
            }
            
            if (this.numberOfDays != null) {
                String property = props.getProperty("purge.lastDate");
                try {
                    Date lastDate = DF.get().parse(property);
                    Date currentDate = new Date();
                    
                    double diffInDays = differenceInDays(currentDate, lastDate); 
                    if (diffInDays >= this.numberOfDays) {
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
            if (this.groupIds.isEmpty()) {
                logger.println("Purging whole repository!");
                repoRoot.deleteContents();
            } else {
                for (String groupId : this.groupIds) {
                    String[] pathElements = groupId.split("\\.");
                    FilePath groupIdRoot = repoRoot;
                    for (String element : pathElements) {
                        groupIdRoot = groupIdRoot.child(element);
                    }
                    logger.println("Cleaning: " + groupIdRoot);
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

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public String getDisplayName() {
            return "Purges local Maven repositories";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return (item instanceof MavenModuleSet)
                || (item instanceof ProjectWithMaven);
        }
        
    }
}
