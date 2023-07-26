package com.monday_consulting.maven.plugins.fsm.util.resolver;

/*
Copyright 2016-2020 Monday Consulting GmbH

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

import com.monday_consulting.maven.plugins.fsm.jaxb.ModuleType;
import com.monday_consulting.maven.plugins.fsm.util.Module;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.*;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepositoryManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the resolution of an artifact within the maven reactor or repository.
 *
 * @author Kassim Hölting
 * @since 1.0.0
 */
public class MavenGetArtifactsResolver implements IResolver {

    private final Log log;
    private final MavenSession session;
    private final ArtifactResolver artifactResolver;
    private final List<MavenProject> reactorProjects;
    private final ProjectBuilder projectBuilder;
    private final MavenProject parentMavenProject;

    public MavenGetArtifactsResolver(final Log log, final MavenSession session, final ArtifactResolver artifactResolver,
                                     final List<MavenProject> reactorProjects, final ProjectBuilder projectBuilder,
                                     final MavenProject parentMavenProject) {
        this.log = log;
        this.session = session;
        this.artifactResolver = artifactResolver;
        this.reactorProjects = reactorProjects;
        this.projectBuilder = projectBuilder;
        this.parentMavenProject = parentMavenProject;
    }

    /**
     * {@inheritDoc}
     */
    public Module resolve(final ModuleType moduleType, final List<String> scopes) throws MojoFailureException, MojoExecutionException {
        log.debug("Resolving module for dependency tag " + moduleType.getDependencyTagValueInXml());
        final Module module = new Module(log, moduleType, scopes);
        List<MavenProject> mavenProjects = getMavenProjects(module);
        module.setProjects(mavenProjects);
        return module;
    }

    private List<MavenProject> getMavenProjects(final Module module) {
        List<MavenProject> mavenProjects = new ArrayList<>();

        for (final DefaultArtifact artifactInfo : module.getArtifacts()) {
            // Order: reactor project, local project, maven repository
            MavenProject mavenProject = getMavenProjectViaReactor(artifactInfo);
            if (mavenProject != null) {
                mavenProjects.add(mavenProject);
            } else {
                mavenProject = getMavenProjectViaLocalWorkspace(artifactInfo);
                if (mavenProject != null) {
                    mavenProjects.add(mavenProject);
                } else {
                    // this succeeds or throws
                    mavenProjects.add(getMavenProjectViaRepository(artifactInfo));
                }
            }
        }

        return mavenProjects.stream().map(project -> {
            project = project.clone();
            // without an artifact filter, artifact resolution may fail
            project.setArtifactFilter((a) -> true);
            return project;
        }).collect(Collectors.toList());
    }

    private MavenProject getMavenProjectViaReactor(final DefaultArtifact artifact) {
        MavenProject mavenProject = null;
        boolean moduleInReactor = false;

        final String logMessagePrefix = "Module " + artifact.getGroupId() + ":" + artifact.getArtifactId();

        for (final MavenProject prj : reactorProjects) {
            if ((prj.getArtifactId().equals(artifact.getArtifactId())) && (prj.getGroupId().equals(artifact.getGroupId()))) {
                if (moduleInReactor) {
                    log.error(logMessagePrefix + " found twice in reactor!");
                } else {
                    log.debug(logMessagePrefix + " found in reactor!");
                    moduleInReactor = true;
                    mavenProject = prj;
                }
            }
        }

        return mavenProject;
    }

    private MavenProject getMavenProjectViaLocalWorkspace(final DefaultArtifact artifact) {
        MavenProject rootProject = parentMavenProject.getParent();
        if (rootProject != null && rootProject.getGroupId().equals(artifact.getGroupId())) {
            log.debug("Module " + artifact.getGroupId() + ":" + artifact.getArtifactId()
                    + " might exist locally.");
            File localArtifactPom = rootProject.getBasedir().toPath()
                    .resolve(artifact.getArtifactId()).resolve("pom.xml")
                    .toFile();
            if (localArtifactPom.exists()) {
                try {
                    MavenProject result = projectBuilder.build(localArtifactPom, buildingRequest()).getProject();
                    if (result != null) {
                        Build build = result.getBuild();
                        if (build != null) {
                            File artifactFile = new File(build.getDirectory()).toPath()
                                    .resolve(build.getFinalName() + "." + result.getPackaging()).toFile();
                            if (artifactFile.exists()) {
                                result.getArtifact().setFile(artifactFile);
                                log.info("Resolved " + artifact + " via local project " + localArtifactPom.getPath());
                                return result;
                            }
                        }
                    }
                } catch (ProjectBuildingException e) {
                    log.debug("Failed to build local project " + localArtifactPom + ".");
                }
            }
        }
        return null;
    }

    private ProjectBuildingRequest buildingRequest() {
        return new DefaultProjectBuildingRequest(session.getProjectBuildingRequest()).setResolveDependencies(true);
    }

    private DefaultArtifact artifactWithVersion(DefaultArtifact artifact, String version) {
        return new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                artifact.getExtension(), version);
    }

    private MavenProject getMavenProjectViaRepository(DefaultArtifact artifact) {
        log.debug("Module " + artifact.getGroupId() + ":" + artifact.getArtifactId()
                + " not found in reactor, trying to find it in the local repository.");
        try {
            final ProjectBuildingRequest request = buildingRequest();
            request.setProject(null);

            final LocalRepositoryManager localRepositoryManager = session.getRepositorySession().getLocalRepositoryManager();
            final File repoBasedir = localRepositoryManager.getRepository().getBasedir();

            if ((artifact.getVersion() == null || artifact.getVersion().isEmpty())
                    && parentMavenProject.getGroupId().equals(artifact.getGroupId())) {
                log.info("Assuming project version " + parentMavenProject.getVersion()
                        + " for artifact " + artifact);
                artifact = artifactWithVersion(artifact, parentMavenProject.getVersion());
            }

            final String pathForLocalArtifact = localRepositoryManager.getPathForLocalArtifact(artifact);
            final File moduleArtifactFile = new File(repoBasedir, pathForLocalArtifact);

            // the module pom artifact to build maven project
            final DefaultArtifact pomArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                    artifact.getClassifier(), "pom", artifact.getVersion());

            final String localArtifactPath = localRepositoryManager.getPathForLocalArtifact(pomArtifact);

            final File projectFile = new File(repoBasedir, localArtifactPath);

            MavenProject result;
            try {
                result = projectBuilder.build(projectFile, request).getProject();

                if (!moduleArtifactFile.exists()) {
                    resolveArtifact(artifact);
                }
            } catch (ProjectBuildingException e) {
                log.debug("failed... try to resolve " + artifact.getArtifactId() + " from remote repository...");
                final Artifact mavenArtifact = new org.apache.maven.artifact.DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getVersion(), null, artifact.getExtension(), artifact.getClassifier(), new DefaultArtifactHandler());
                result = projectBuilder.build(mavenArtifact, request).getProject();

                resolveArtifact(artifact);
            }

            if (result != null) {
                log.debug("Dependency resolved: " + artifact.getArtifactId() + ":" + artifact.getVersion());
                result.getArtifact().setFile(moduleArtifactFile);
                result.setParent(parentMavenProject);
            } else {
                throw new RuntimeException("No dependency for " + artifact.getArtifactId() + " found in local or remote repository.");
            }

            return result;
        } catch (ProjectBuildingException e) {
            throw new RuntimeException("No dependency for " + artifact.getArtifactId() + " found in local or remote repository.", e);
        }
    }

    private void resolveArtifact(final DefaultArtifact moduleArtifact) {
        log.info("Try to resolve artifact for " + moduleArtifact.getArtifactId() + " from remote repository...");

        DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
        coordinate.setGroupId(moduleArtifact.getGroupId());
        coordinate.setArtifactId(moduleArtifact.getArtifactId());
        coordinate.setVersion(moduleArtifact.getVersion());
        coordinate.setExtension(moduleArtifact.getExtension());
        coordinate.setClassifier(moduleArtifact.getClassifier());

        try {
            artifactResolver.resolveArtifact(buildingRequest(), coordinate);
        } catch (ArtifactResolverException e) {
            log.error("Failed to resolve artifact " + coordinate + ".");
            throw new RuntimeException(e);
        }
    }
}
