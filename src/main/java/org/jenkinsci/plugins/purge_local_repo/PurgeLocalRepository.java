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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.maven.repository.RepositorySystem;
import org.kohsuke.stapler.DataBoundConstructor;

public class PurgeLocalRepository extends BuildWrapper {

    private List<String> groupIds;
    private Integer numberOfBuilds;
    private Integer numberOfDays;
    
    @DataBoundConstructor
    public PurgeLocalRepository(String groupIds, Integer numberOfBuilds, Integer numberOfDays) {
        String grpIds = Util.fixNull(groupIds);
        this.groupIds = Arrays.asList(grpIds.split(","));
        // TODO: check <= 0
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
            
            // TODO: do the same for numberOfDays
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
            props.setProperty("purge.lastDate", new Date().toGMTString());
            props.store(lastPurgeFile.write(), "settings of the purge-local-repo-plugin");
        }
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
