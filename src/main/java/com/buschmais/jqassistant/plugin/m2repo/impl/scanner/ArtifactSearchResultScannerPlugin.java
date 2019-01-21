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
import com.buschmais.jqassistant.plugin.maven3.api.artifact.Coordinates;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.MavenArtifactHelper;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenArtifactDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenPomXmlDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.PomModelBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.maven.index.ArtifactInfo;
import org.eclipse.aether.artifact.Artifact;
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
    private static final int QUEUE_CAPACITY = 100;

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
     * @param scanner              the {@link Scanner}
     * @param artifactProvider     the {@link AetherArtifactProvider}
     * @param artifactSearchResult the {@link ArtifactSearchResult}
     */
    private void resolveAndScan(Scanner scanner, ArtifactProvider artifactProvider, ArtifactSearchResult artifactSearchResult) throws IOException {
        ScannerContext context = scanner.getContext();
        Store store = context.getStore();
        PomModelBuilder effectiveModelBuilder = new EffectiveModelBuilder(artifactProvider);
        MavenRepositoryDescriptor repositoryDescriptor = artifactProvider.getRepositoryDescriptor();

        BlockingQueue<ArtifactTask.Result> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> new Thread(r, ArtifactTask.class.getSimpleName()));
        pool.submit(new ArtifactTask(artifactSearchResult, artifactFilter, scanArtifacts, queue, artifactProvider));

        Cache<String, MavenPomXmlDescriptor> cache = Caffeine.newBuilder().maximumSize(256).build();

        ArtifactTask.Result result;
        try {
            do {
                result = queue.take();
                if (result != ArtifactTask.Result.LAST) {
                    ArtifactInfo artifactInfo = result.getArtifactInfo();
                    Artifact modelArtifact = result.getModelArtifactResult().getArtifact();
                    long lastModified = result.getLastModified();
                    LOGGER.debug("Processing '{}'.", artifactInfo);
                    boolean snapshot = modelArtifact.isSnapshot();
                    MavenPomXmlDescriptor modelDescriptor = getModel(modelArtifact, snapshot, lastModified, repositoryDescriptor, scanner, effectiveModelBuilder, cache);
                    if (!artifactInfo.getPackaging().equals(EXTENSION_POM)) { // The POM itself is the artifact
                        Coordinates artifactCoordinates = new ArtifactInfoCoordinates(artifactInfo, modelArtifact.getBaseVersion(), snapshot);
                        MavenArtifactDescriptor mavenArtifactDescriptor = repositoryDescriptor.findArtifact(MavenArtifactHelper.getId(artifactCoordinates));
                        if (mavenArtifactDescriptor == null) {
                            if (result.getArtifactResult().isPresent()) {
                                // Scan artifact from repository
                                ArtifactResult artifactResult = result.getArtifactResult().get();
                                Artifact artifact = artifactResult.getArtifact();
                                LOGGER.info("Scanning artifact '{}'.", artifact);
                                Descriptor descriptor = scan(artifactResult.getArtifact(), scanner);
                                mavenArtifactDescriptor = store.addDescriptorType(descriptor, MavenArtifactDescriptor.class);
                            } else {
                                // Resolve artifact without scanning
                                mavenArtifactDescriptor = scanner.getContext().peek(ArtifactResolver.class).resolve(artifactCoordinates, scanner.getContext());
                            }
                            markReleaseOrSnaphot(mavenArtifactDescriptor, MavenArtifactDescriptor.class, snapshot, lastModified, store);
                            if (mavenArtifactDescriptor.getDescribedBy() == null) {
                                mavenArtifactDescriptor.setDescribedBy(modelDescriptor);
                            }
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
     * Determines the {@link MavenPomXmlDescriptor} of a POM {@link Artifact}.
     *
     * @param modelArtifact         The {@link Artifact} representing the model.
     * @param snapshot              <code>true</code> if the artifact represents a snapshot.
     * @param lastModified          The last modified timestamp.
     * @param repositoryDescriptor  The {@link MavenRepositoryDescriptor}.
     * @param scanner               The {@link Scanner}
     * @param effectiveModelBuilder The {@link PomModelBuilder}.
     * @param cache                 The {@link Cache}.
     * @return The {@link MavenPomXmlDescriptor} representing the model.
     */
    private MavenPomXmlDescriptor getModel(Artifact modelArtifact, boolean snapshot, long lastModified, MavenRepositoryDescriptor repositoryDescriptor, Scanner scanner,
                                           PomModelBuilder effectiveModelBuilder, Cache<String, MavenPomXmlDescriptor> cache) {
        String coordinates = MavenArtifactHelper.getId(new AetherArtifactCoordinates(modelArtifact));
        return cache.get(coordinates, key -> {
            MavenPomXmlDescriptor modelDescriptor = repositoryDescriptor.findModel(key);
            if (modelDescriptor == null) {
                scanner.getContext().push(PomModelBuilder.class, effectiveModelBuilder);
                try {
                    LOGGER.info("Scanning model '{}'.", modelArtifact);
                    modelDescriptor = scan(modelArtifact, scanner);
                    if (!key.equals(modelDescriptor.getFullQualifiedName())) {
                        // The fqn is already set by the scanner, but may be not consistent with the
                        // required fqn for repositories (e.g. if model could not be parsed. So it is
                        // overriden here).
                        LOGGER.warn("Model coordinates '{}' do not match expected '{}', overriding.", modelDescriptor.getFullQualifiedName(), key);
                        modelDescriptor.setFullQualifiedName(key);
                    }
                } finally {
                    scanner.getContext().pop(PomModelBuilder.class);
                }
                markReleaseOrSnaphot(modelDescriptor, MavenPomXmlDescriptor.class, snapshot, lastModified, scanner.getContext().getStore());
                repositoryDescriptor.getContainedModels().add(modelDescriptor);
            }
            return modelDescriptor;
        });
    }

    /**
     * Scans the given {@link Artifact}.
     *
     * @param artifact The {@link Artifact}.
     * @param scanner  The scanner.
     * @param <D>      The expected {@link Descriptor} type.
     * @return The {@link Descriptor}.
     */
    private <D extends Descriptor> D scan(Artifact artifact, Scanner scanner) {
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
     * Adds a `Release` or `Snapshot` label to the given maven descriptor depending
     * on the artifact version type.
     *
     * @param descriptor   the descriptor
     * @param type         the expected descriptor type
     * @param snapshot     if the artifact is a snapshot
     * @param lastModified last modified date (for snapshots only)
     * @param store        the store
     */
    private <D extends MavenDescriptor> void markReleaseOrSnaphot(D descriptor, Class<? extends D> type, boolean snapshot, Long lastModified,
                                                                  Store store) {
        if (snapshot) {
            MavenSnapshotDescriptor snapshotDescriptor = store.addDescriptorType(descriptor, MavenSnapshotDescriptor.class);
            snapshotDescriptor.setLastModified(lastModified);
        } else {
            store.addDescriptorType(descriptor, MavenReleaseDescriptor.class, type);
        }
    }

    /**
     * Extracts a list of artifact filters from the given property.
     *
     * @param propertyName The name of the property.
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
