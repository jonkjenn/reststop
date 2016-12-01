/*
 * Copyright 2015 Kantega AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kantega.reststop.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.shared.invoker.Invoker;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.jetty.maven.plugin.JettyWebAppContext;
import org.eclipse.jetty.server.Server;
import org.kantega.reststop.classloaderutils.BuildSystem;
import org.kantega.reststop.classloaderutils.PluginClassLoader;
import org.kantega.reststop.classloaderutils.PluginInfo;

import java.awt.*;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 *
 */
@Mojo(name = "run",
        defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST,
        requiresDirectInvocation = true,
        requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.PACKAGE)
public class RunMojo extends AbstractReststopRunMojo {

    @Parameter(defaultValue = "${path}")
    private String path;

    @Parameter(defaultValue = "${openProjectDir}")
    private boolean openProjectDir;

    @Parameter(defaultValue = "localhost")
    private String hostname;

    @Component
    private Invoker invoker;

    @Component
    private ModelBuilder modelBuilder;

    @Parameter(defaultValue="${project.artifacts}")
    Set<org.apache.maven.artifact.Artifact> projectArtifacts;

    @Override
    protected void customizeContext(JettyWebAppContext context) {
        if("war".equals(mavenProject.getPackaging())) {
            context.setClasses(classesDirectory);
            context.setWebInfLib(getDependencyFiles());
        }

        context.addSystemClass("org.kantega.reststop.classloaderutils.");
        registerBuildSystem(invoker);
    }

    private List<File> getDependencyFiles ()
    {
        List<File> dependencyFiles = new ArrayList<File>();
        for (Iterator<org.apache.maven.artifact.Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
        {
            org.apache.maven.artifact.Artifact artifact = (org.apache.maven.artifact.Artifact) iter.next();

            // Include runtime and compile time libraries, and possibly test libs too
            if(artifact.getType().equals("war"))
            {
                continue;
            }

            if (org.apache.maven.artifact.Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
                continue; //never add dependencies of scope=provided to the webapp's classpath (see also <useProvidedScope> param)

            if (org.apache.maven.artifact.Artifact.SCOPE_TEST.equals(artifact.getScope()))
                continue; //only add dependencies of scope=test if explicitly required

            dependencyFiles.add(artifact.getFile());
            getLog().debug( "Adding artifact " + artifact.getFile().getName() + " with scope "+artifact.getScope()+" for WEB-INF/lib " );
        }

        return dependencyFiles;
    }
    private void registerBuildSystem(Invoker invoker) {
        BuildSystem.instance = new BuildSystem() {
            @Override
            public boolean needsRefresh(PluginClassLoader cl) {
                if(cl.getPluginInfo().getSourceDirectory() == null) {
                    return false;
                }

                File pomXml = new File(cl.getPluginInfo().getSourceDirectory(), "pom.xml");

                return cl.getCreationTime() < pomXml.lastModified();
            }

            @Override
            public PluginInfo refresh(PluginInfo pluginInfo) {


                Resolver resolver = new Resolver(repoSystem, repoSession, remoteRepos, getLog());

                try {
                    File pomFile = new File(pluginInfo.getSourceDirectory(), "pom.xml");

                    PluginInfo info = new PluginInfo();
                    info.setGroupId(pluginInfo.getGroupId());
                    info.setArtifactId(pluginInfo.getArtifactId());
                    info.setVersion(pluginInfo.getVersion());
                    info.setSourceDirectory(pluginInfo.getSourceDirectory());

                    Artifact pluginArtifact = resolver.resolveArtifact(info.getGroupId() +":" + info.getArtifactId() +":" + info.getVersion());
                    info.setFile(pluginArtifact.getFile());
                    resolver.resolveClasspaths(info, createCollectRequest(buildModel(pomFile)));
                    return info;
                } catch ( MojoFailureException | DependencyResolutionException | MojoExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            private CollectRequest createCollectRequest(Model model) {
                CollectRequest collectRequest = new CollectRequest();

                for (RemoteRepository repo : remoteRepos) {
                    collectRequest.addRepository(repo);
                }
                for (Dependency dependency : model.getDependencies()) {
                    collectRequest.addDependency(mavenDep2AetherDep(dependency));
                }
                DependencyManagement depm = model.getDependencyManagement();
                if(depm != null) {
                    for (Dependency dependency : depm.getDependencies()) {
                        collectRequest = collectRequest.addManagedDependency(mavenDep2AetherDep(dependency));
                    }
                }

                return collectRequest;
            }

            private org.eclipse.aether.graph.Dependency mavenDep2AetherDep(Dependency dependency) {
                Artifact artifact = new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), dependency.getClassifier(), dependency.getType(), dependency.getVersion());
                org.eclipse.aether.graph.Dependency aetherDep = new org.eclipse.aether.graph.Dependency(artifact, dependency.getScope());
                if(dependency.getExclusions() != null) {
                    List<Exclusion> exclusions = new ArrayList<>();
                    dependency.getExclusions().forEach( e -> {
                        exclusions.add(new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
                    });
                    aetherDep = aetherDep.setExclusions(exclusions);
                }
                return aetherDep;
            }

            private Model buildModel(File pomFile) {
                try {
                    DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
                    request.setModelSource(new FileModelSource(pomFile));
                    ModelBuildingResult build = modelBuilder.build(request);
                    return build.getEffectiveModel();
                } catch (ModelBuildingException e) {
                    throw  new RuntimeException(e);
                }
            }
        };
    }

    @Override
    protected void afterServerStart(Server server, int port) throws MojoFailureException {
        try {
            if( hostname == null || hostname.length() == 0)
                hostname = "localhost";
            openInBrowser("http://"+ hostname + ":" + port);
            server.join();
        } catch (InterruptedException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    private void openInBrowser(String url) throws MojoFailureException {
        try {
            if (Desktop.isDesktopSupported()) {
                if (path != null) {
                    url += "/" + path;
                }
                DesktopApi.browse(new URI(url));
                if (openProjectDir) {
                    DesktopApi.open(mavenProject.getBasedir());
                }
            }

        } catch (URISyntaxException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    @Override
    protected List<Plugin> getPlugins() {
        List<Plugin> plugins = new ArrayList<>(super.getPlugins());

        if(mavenProject.getPackaging().equals("jar")) {
            Plugin projectPlugin = new Plugin(mavenProject.getGroupId(), mavenProject.getArtifactId(), mavenProject.getVersion());
            projectPlugin.setSourceDirectory(mavenProject.getBasedir());
            plugins.add(projectPlugin);
        }

        addDevelopmentPlugins(plugins);


        return plugins;
    }

}
