package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.common.api.scanner.FileResolver;
import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenReleaseDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenSnapshotDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.AetherArtifactCoordinates;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.MavenArtifactHelper;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenArtifactDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenPomXmlDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.PomModelBuilder;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin for (remote) maven artifacts.
 * 
 * @author pherklotz
 */
public class ArtifactSearchResultScannerPlugin extends AbstractScannerPlugin<ArtifactSearchResult, MavenRepositoryDescriptor> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactSearchResultScannerPlugin.class);

    private static final String PROPERTY_NAME_ARTIFACTS_KEEP = "m2repo.artifacts.keep";
    private static final String PROPERTY_NAME_ARTIFACTS_SCAN = "m2repo.artifacts.scan";
    private static final String PROPERTY_NAME_FILTER_INCLUDES = "m2repo.filter.includes";
    private static final String PROPERTY_NAME_FILTER_EXCLUDES = "m2repo.filter.excludes";
    private static final String EXTENSION_POM = "pom";

    private boolean keepArtifacts;
    private boolean scanArtifacts;
    private ArtifactFilter artifactFilter;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void configure() {
        scanArtifacts = getBooleanProperty(PROPERTY_NAME_ARTIFACTS_SCAN, true);
        keepArtifacts = getBooleanProperty(PROPERTY_NAME_ARTIFACTS_KEEP, true);

        List<String> includeFilter = getFilterPattern(PROPERTY_NAME_FILTER_INCLUDES);
        List<String> excludeFilter = getFilterPattern(PROPERTY_NAME_FILTER_EXCLUDES);
        artifactFilter = new ArtifactFilter(includeFilter, excludeFilter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accepts(ArtifactSearchResult item, String path, Scope scope) {
        return MavenScope.REPOSITORY.equals(scope);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MavenRepositoryDescriptor scan(ArtifactSearchResult artifactSearchResult, String path, Scope scope, Scanner scanner) throws IOException {
        ArtifactProvider artifactProvider = scanner.getContext().peek(ArtifactProvider.class);
        // register file resolver strategy to identify repository artifacts
        scanner.getContext().push(FileResolver.class, artifactProvider.getFileResolver());
        scanner.getContext().push(ArtifactResolver.class, artifactProvider.getArtifactResolver());
        try {
            resolveAndScan(scanner, artifactProvider, artifactSearchResult);
        } finally {
            scanner.getContext().pop(ArtifactResolver.class);
            scanner.getContext().pop(FileResolver.class);
        }
        return artifactProvider.getRepositoryDescriptor();
    }

    /**
     * Resolves, scans and add the artifact to the
     * {@link MavenRepositoryDescriptor}.
     *
     * @param scanner
     *            the {@link Scanner}
     * @param artifactProvider
     *            the {@link AetherArtifactProvider}
     * @param artifactSearchResult
     *            the {@link ArtifactSearchResult}
     */
    private void resolveAndScan(Scanner scanner, ArtifactProvider artifactProvider, ArtifactSearchResult artifactSearchResult) throws IOException {
        ScannerContext context = scanner.getContext();
        Store store = context.getStore();
        PomModelBuilder effectiveModelBuilder = new EffectiveModelBuilder(artifactProvider);
        MavenRepositoryDescriptor repositoryDescriptor = artifactProvider.getRepositoryDescriptor();

        BlockingQueue<ArtifactTask.Result> queue = new LinkedBlockingDeque<>(10);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        pool.submit(new ArtifactTask(artifactSearchResult, artifactFilter, scanArtifacts, queue, artifactProvider));

        ArtifactTask.Result result;
        try {
            do {
                result = queue.take();
                if (result != ArtifactTask.Result.LAST) {
                    Artifact resolvedModelArtifact = result.getModelArtifactResult().getArtifact();
                    Long lastModified = result.getLastModified();
                    MavenPomXmlDescriptor modelDescriptor = findModel(repositoryDescriptor, resolvedModelArtifact);
                    if (modelDescriptor == null) {
                        context.push(PomModelBuilder.class, effectiveModelBuilder);
                        try {
                            LOGGER.info("Scanning model '{}'.", resolvedModelArtifact);
                            modelDescriptor = scanArtifactFile(resolvedModelArtifact, scanner);
                        } finally {
                            context.pop(PomModelBuilder.class);
                        }
                        modelDescriptor = markReleaseOrSnaphot(modelDescriptor, MavenPomXmlDescriptor.class, resolvedModelArtifact, lastModified, store);
                        repositoryDescriptor.getContainedModels().add(modelDescriptor);
                    }
                    if (result.getArtifactResult().isPresent()) {
                        ArtifactResult artifactResult = result.getArtifactResult().get();
                        Artifact artifact = artifactResult.getArtifact();
                        if (!artifact.getExtension().equals(EXTENSION_POM)) {
                            Descriptor descriptor = scanArtifactFile(artifactResult.getArtifact(), scanner);
                            MavenArtifactDescriptor descriptorToAdd = store.addDescriptorType(descriptor, MavenArtifactDescriptor.class);
                            MavenArtifactDescriptor mavenArtifactDescriptor = markReleaseOrSnaphot(descriptorToAdd, MavenArtifactDescriptor.class, artifact,
                                    lastModified, store);
                            MavenArtifactHelper.setId(mavenArtifactDescriptor, new RepositoryArtifactCoordinates(artifact, lastModified));
                            MavenArtifactHelper.setCoordinates(mavenArtifactDescriptor, new RepositoryArtifactCoordinates(artifact, lastModified));
                            modelDescriptor.getDescribes().add(mavenArtifactDescriptor);
                            repositoryDescriptor.getContainedArtifacts().add(mavenArtifactDescriptor);
                        }
                    }
                }
            } while (result != ArtifactTask.Result.LAST);
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while waiting for artifact result", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Scans the given {@link Artifact}.
     * 
     * @param artifact
     *            The {@link Artifact}.
     * @param scanner
     *            The scanner.
     * @param <D>
     *            The expected {@link Descriptor} type.
     * @return The {@link Descriptor}.
     */
    private <D extends Descriptor> D scanArtifactFile(Artifact artifact, Scanner scanner) {
        File artifactFile = artifact.getFile();
        try {
            return scanner.scan(artifactFile, artifactFile.getAbsolutePath(), null);
        } finally {
            if (!keepArtifacts) {
                artifactFile.delete();
            }
        }
    }

    /**
     * Returns {@link MavenPomXmlDescriptor} from the given repository descriptor or
     * <code>null</code>.
     *
     * @param repositoryDescriptor
     *            the repository containing the model.
     * @param resolvedModelArtifact
     *            The resolved model artifact (i.e. in case of a snapshot containing
     *            the timestamp/buildnumber in the version.)
     * @return a {@link MavenPomXmlDescriptor} or `null`.
     */
    private MavenPomXmlDescriptor findModel(MavenRepositoryDescriptor repositoryDescriptor, Artifact resolvedModelArtifact) {
        Artifact resolvedMainArtifact = new DefaultArtifact(resolvedModelArtifact.getGroupId(), resolvedModelArtifact.getArtifactId(),
                resolvedModelArtifact.getExtension(), resolvedModelArtifact.getVersion());
        String coordinates = MavenArtifactHelper.getId(new AetherArtifactCoordinates(resolvedMainArtifact));
        return repositoryDescriptor.findModel(coordinates);
    }

    /**
     * Adds a `Release` or `Snapshot` label to the given maven descriptor depending
     * on the artifact version type.
     * 
     * @param descriptor
     *            the descriptor
     * @param type
     *            the expected descriptor type
     * @param resolvedArtifact
     *            the resolved artifact
     * @param lastModified
     *            last modified date (for snapshots only)
     * @param store
     *            the store
     * @return the new created resolvedArtifact descriptor
     */
    private <D extends MavenDescriptor> D markReleaseOrSnaphot(D descriptor, Class<? extends D> type, Artifact resolvedArtifact, Long lastModified,
            Store store) {
        if (resolvedArtifact.isSnapshot()) {
            MavenSnapshotDescriptor snapshotDescriptor = store.addDescriptorType(descriptor, MavenSnapshotDescriptor.class);
            snapshotDescriptor.setLastModified(lastModified);
            return type.cast(snapshotDescriptor);
        } else {
            return store.addDescriptorType(descriptor, MavenReleaseDescriptor.class, type);
        }
    }

    /**
     * Extracts a list of artifact filters from the given property.
     * 
     * @param propertyName
     *            The name of the property.
     * @return The list of artifact patterns.
     */
    private List<String> getFilterPattern(String propertyName) {
        String patterns = getStringProperty(propertyName, null);
        if (patterns == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String pattern : patterns.split(",")) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
