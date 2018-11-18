package com.hanhuy.gradle.discovery;

import com.android.builder.model.AndroidProject;
import com.android.builder.model.PackagingOptions;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * @author pfnguyen
 */
public class GradleBuildPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry registry;

    @Inject
    public GradleBuildPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        registry.register(new GradleBuildModelBuilder(registry));
    }

    private static class GradleBuildModelBuilder implements ToolingModelBuilder {
        private final ToolingModelBuilderRegistry registry;
        public GradleBuildModelBuilder(ToolingModelBuilderRegistry registry) {
            this.registry = registry;
        }
        @Override
        public boolean canBuild(String modelName) {
            return modelName.equals(GradleBuildModel.class.getName());
        }

        @Override
        public Object buildAll(String modelName, Project project) {
            return new GradleBuildM(project, registry);
        }
    }

    private static Method findMethod(Object instance, String name)
            throws NoSuchMethodException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Method method = clazz.getDeclaredMethod(name);


                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }

                return method;
            } catch (NoSuchMethodException e) {
                // ignore and search next
            }
        }

        throw new NoSuchMethodException("Method " + name);
    }
    public static class GradleBuildM implements Serializable {
        private final static String GRADLE_PROJECT = GradleProject.class.getName();
        private final static String ANDROID_PROJECT = AndroidProject.class.getName();
        private final AndroidDiscoveryModel discovery;
        private final RepositoryListModel repositories;
        private final Object gradleProject;
        private final Object androidProject;
        private final Object packagingOptions;
        public GradleBuildM(Project project, ToolingModelBuilderRegistry registry) {
            ToolingModelBuilder b;
            discovery = new AndroidDiscovery(
                    project.getPlugins().hasPlugin("com.android.application"),
                    project.getPlugins().hasPlugin("com.android.library"));
            repositories = new RM(project.getRepositories());
            b = registry.getBuilder(GRADLE_PROJECT);
            gradleProject = b.buildAll(GRADLE_PROJECT, project);
            if (discovery.isApplication() || discovery.isLibrary()) {
                b = registry.getBuilder(ANDROID_PROJECT);
                androidProject = b.buildAll(ANDROID_PROJECT, project);
                packagingOptions = new PO(project.getExtensions().findByName("android"));
            } else {
                androidProject = null;
                packagingOptions = null;
            }
        }
        public RepositoryListModel getRepositories() {
            return repositories;
        }

        public AndroidDiscoveryModel getDiscovery() {
            return discovery;
        }

        public Object getAndroidProject() {
            return androidProject;
        }

        public Object getGradleProject() {
            return gradleProject;
        }

        public Object getPackagingOptions() { return packagingOptions; }
    }

    public static class PO implements PackagingOptions, Serializable {
        private final Set<String> excludes;
        private final Set<String> firsts;
        private final Set<String> merges;
        private final Set<String> nostrip;
        @SuppressWarnings("unchecked")
        public PO(Object extension) {
            Set<String> e = Collections.EMPTY_SET, f = Collections.EMPTY_SET, m = Collections.EMPTY_SET, ns = Collections.EMPTY_SET;
            try {
                Object po = findMethod(extension, "getPackagingOptions").invoke(extension);
                e    = (Set<String>) findMethod(po, "getExcludes").invoke(po);
                f    = (Set<String>) findMethod(po, "getPickFirsts").invoke(po);
                m    = (Set<String>) findMethod(po, "getMerges").invoke(po);
                ns   = (Set<String>) findMethod(po, "getDoNotStrip").invoke(po);
            } catch (Exception x) {
                // noop
            }
            excludes = e;
            firsts   = f;
            merges   = m;
            nostrip  = ns;

        }
        @Override
        public Set<String> getExcludes() {
            return excludes;
        }

        @Override
        public Set<String> getPickFirsts() {
            return firsts;
        }

        @Override
        public Set<String> getMerges() {
            return merges;
        }

        @Override
        public Set<String> getDoNotStrip() {
            return nostrip;
        }
    }
    public static class AndroidDiscovery implements Serializable, AndroidDiscoveryModel {
        public final boolean hasApplicationPlugin;

        public boolean isLibrary() {
            return hasLibraryPlugin;
        }

        public final boolean hasLibraryPlugin;

        public AndroidDiscovery(boolean hasApplicationPlugin, boolean hasLibraryPlugin) {
            this.hasApplicationPlugin = hasApplicationPlugin;
            this.hasLibraryPlugin = hasLibraryPlugin;
        }

        public boolean isApplication() {
            return hasApplicationPlugin;
        }
    }
    public static class RM implements Serializable, RepositoryListModel {
        private final Collection<MavenRepositoryModel> resolvers = new ArrayList<MavenRepositoryModel>();
        public RM(RepositoryHandler rh) {
            for (ArtifactRepository r : rh) {
                if (r instanceof MavenArtifactRepository) {
                    resolvers.add(new MAR((MavenArtifactRepository)r));
                }
            }
        }
        public Collection<MavenRepositoryModel> getResolvers() {
            return resolvers;
        }
    }
    public static class MAR implements MavenRepositoryModel, Serializable {
        private final URI url;
        private final Set<URI> artifactUrls;
        private final String name;
        public MAR(MavenArtifactRepository mar) {
            url = mar.getUrl();
            artifactUrls = mar.getArtifactUrls();
            name = mar.getName();
        }
        public URI getUrl() {
            return url;
        }


        @Override
        public Set<URI> getArtifactUrls() {
            return artifactUrls;
        }


        @Override
        public String getName() {
            return name;
        }
    }
}

