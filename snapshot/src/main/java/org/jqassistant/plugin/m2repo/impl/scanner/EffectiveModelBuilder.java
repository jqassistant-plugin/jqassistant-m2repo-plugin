package org.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;

import com.buschmais.jqassistant.plugin.maven3.api.scanner.EffectiveModel;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.PomModelBuilder;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.RawModelBuilder;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.*;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.validation.ModelValidator;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jqassistant.plugin.m2repo.api.ArtifactProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a POM model builder which resolves the effective model.
 */
public class EffectiveModelBuilder implements PomModelBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EffectiveModelBuilder.class);

    private final ModelResolverImpl modelResolver;
    private final RawModelBuilder rawModelBuilder;
    private final DefaultModelBuilder builder = new DefaultModelBuilderFactory().newInstance();

    /**
     * Constructor.
     *
     * @param artifactProvider
     *            The artifact provider.
     */
    public EffectiveModelBuilder(ArtifactProvider artifactProvider) {
        this.modelResolver = new ModelResolverImpl(artifactProvider);
        this.rawModelBuilder = new RawModelBuilder();
    }

    @Override
    public Model getModel(File pomFile) throws IOException {
        ModelBuildingRequest req = new DefaultModelBuildingRequest();
        req.setProcessPlugins(false);
        req.setPomFile(pomFile);
        req.setModelResolver(modelResolver);
        req.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        req.setSystemProperties(System.getProperties());
        builder.setModelValidator(new ModelValidatorImpl());
        try {
            return new EffectiveModel(builder.build(req).getEffectiveModel());
        } catch (ModelBuildingException e) {
            LOGGER.debug("Cannot build effective model for " + pomFile.getAbsolutePath(), e);
            LOGGER.warn("Building model for '{}' reported errors: {}", pomFile.getAbsolutePath(), e.getProblems());
        }
        LOGGER.warn("Using raw model for " + pomFile.getAbsolutePath());
        return rawModelBuilder.getModel(pomFile);
    }

    /*
     * A custom model validator
     */
    private static class ModelValidatorImpl implements ModelValidator {

        @Override
        public void validateRawModel(Model model, ModelBuildingRequest request, ModelProblemCollector problems) {
        }

        @Override
        public void validateEffectiveModel(Model model, ModelBuildingRequest request, ModelProblemCollector problems) {
            if (problems instanceof ModelProblemCollectorExt) {
                clearProblems(problems, "problems", true);
                clearProblems(problems, "severities", false);
            }
        }

        /**
         * Clear a relevant fields contained in the {@link ModelProblemCollector} to suppress errors.
         *
         * @param problems
         *            The problems.
         * @param field
         *            The field to clear.
         * @param logValue
         *            `true` if the value shall be logged.
         */
        private void clearProblems(ModelProblemCollector problems, String field, boolean logValue) {
            try {
                Field problemsList = problems.getClass().getDeclaredField(field);
                problemsList.setAccessible(true);
                Collection<?> value = (Collection<?>) problemsList.get(problems);
                if (!value.isEmpty()) {
                    if (logValue) {
                        LOGGER.warn("Problems have been detected while validating POM model: {}.", value);
                    }
                    value.clear();
                }
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("Cannot access field " + field, e);
            }
        }
    }

    /**
     * A {@link ModelResolver} implementation.
     */
    public class ModelResolverImpl implements ModelResolver {

        private ArtifactProvider artifactProvider;

        /**
         * Constructor.
         *
         * @param artifactProvider
         *      The {@link ArtifactProvider}
         */
        public ModelResolverImpl(ArtifactProvider artifactProvider) {
            this.artifactProvider = artifactProvider;
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
            Artifact artifact = new DefaultArtifact(groupId, artifactId, null, "pom", version);
            ArtifactResult artifactResult;
            try {
                artifactResult = artifactProvider.getArtifact(artifact);
            } catch (ArtifactResolutionException e) {
                throw new UnresolvableModelException("Cannot resolve artifact.", groupId, artifactId, version, e);
            }
            final File file = artifactResult.getArtifact().getFile();
            return new FileModelSource(file);
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) {
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
        }

        @Override
        public ModelResolver newCopy() {
            return new ModelResolverImpl(artifactProvider);
        }
    }
}
