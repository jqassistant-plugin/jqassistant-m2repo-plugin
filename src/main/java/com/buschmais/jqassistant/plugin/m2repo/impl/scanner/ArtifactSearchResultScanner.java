package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.api.scanner.FileResolver;
import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.api.model.ArtifactInfoDescriptor;
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
import com.buschmais.jqassistant.plugin.maven3.api.scanner.PomModelBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.maven.index.ArtifactInfo;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.resolution.ArtifactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes an {@link ArtifactSearchResult}.
 */
public class ArtifactSearchResultScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactSearchResultScanner.class);

    private static final String EXTENSION_POM = "pom";
    private static final int QUEUE_CAPACITY = 100;

    private final Scanner scanner;
    private final ArtifactProvider artifactProvider;
    private final ArtifactFilter artifactFilter;
    private final boolean scanArtifacts;
    private final boolean keepArtifacts;

    public ArtifactSearchResultScanner(Scanner scanner, ArtifactProvider artifactProvider, ArtifactFilter artifactFilter, boolean scanArtifacts,
            boolean keepArtifacts) {
        this.scanner = scanner;
        this.artifactProvider = artifactProvider;
        this.artifactFilter = artifactFilter;
        this.scanArtifacts = scanArtifacts;
        this.keepArtifacts = keepArtifacts;
    }

    public void scan(ArtifactSearchResult artifactSearchResult) throws IOException {
        // register file resolver strategy to identify repository artifacts
        scanner.getContext().push(FileResolver.class, artifactProvider.getFileResolver());
        scanner.getContext().push(ArtifactResolver.class, artifactProvider.getArtifactResolver());
        try {
            resolveAndScan(scanner, artifactProvider, artifactSearchResult);
        } finally {
            scanner.getContext().pop(ArtifactResolver.class);
            scanner.getContext().pop(FileResolver.class);
        }
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

        BlockingQueue<ArtifactTask.Result> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> new Thread(r, ArtifactTask.class.getSimpleName()));
        pool.submit(new ArtifactTask(artifactSearchResult, artifactFilter, scanArtifacts, queue, artifactProvider));

        Cache<String, MavenPomXmlDescriptor> cache = Caffeine.newBuilder().maximumSize(256).build();

        long artifactCount = 0;
        ArtifactTask.Result result;
        LOGGER.info("Starting scan.");
        try {
            do {
                result = queue.take();
                if (result != ArtifactTask.Result.LAST) {
                    ArtifactInfo artifactInfo = result.getArtifactInfo();
                    Artifact modelArtifact = result.getModelArtifactResult().getArtifact();
                    long lastModified = result.getLastModified();
                    LOGGER.debug("Processing '{}'.", artifactInfo);
                    boolean snapshot = modelArtifact.isSnapshot();
                    MavenPomXmlDescriptor modelDescriptor = getModel(modelArtifact, snapshot, lastModified, repositoryDescriptor, effectiveModelBuilder, cache);
                    // Skip if the POM itself is the artifact
                    if (!EXTENSION_POM.equals(artifactInfo.getPackaging())) { // Note: packaging can be null
                        Coordinates artifactCoordinates = new ArtifactInfoCoordinates(artifactInfo, modelArtifact.getBaseVersion(), snapshot);
                        MavenArtifactDescriptor mavenArtifactDescriptor = repositoryDescriptor.findArtifact(MavenArtifactHelper.getId(artifactCoordinates));
                        if (mavenArtifactDescriptor == null) {
                            if (result.getArtifactResult().isPresent()) {
                                // Scan artifact from repository
                                ArtifactResult artifactResult = result.getArtifactResult().get();
                                Artifact artifact = artifactResult.getArtifact();
                                LOGGER.info("Scanning artifact '{}'.", artifact);
                                Descriptor descriptor = scan(artifactResult.getArtifact());
                                mavenArtifactDescriptor = store.addDescriptorType(descriptor, MavenArtifactDescriptor.class);
                                MavenArtifactHelper.setCoordinates(mavenArtifactDescriptor, artifactCoordinates);
                            } else {
                                // Resolve artifact without scanning
                                mavenArtifactDescriptor = scanner.getContext().peek(ArtifactResolver.class).resolve(artifactCoordinates, scanner.getContext());
                            }
                            markReleaseOrSnaphot(mavenArtifactDescriptor, artifactCoordinates, snapshot, lastModified);
                            // Add DESCRIBES relation from model to artifact if it does not exist yet (e.g.
                            // due to an invalid model)
                            if (!modelDescriptor.getDescribes().contains(mavenArtifactDescriptor)) {
                                modelDescriptor.getDescribes().add(mavenArtifactDescriptor);
                            }
                            repositoryDescriptor.getContainedArtifacts().add(mavenArtifactDescriptor);
                            artifactCount++;
                        }
                    }
                    if (artifactCount % 500 == 0) {
                        LOGGER.info("Processed {} artifacts.", artifactCount);
                        scanner.getContext().getStore().flush();
                    }
                }
            } while (result != ArtifactTask.Result.LAST);
            LOGGER.info("Finished scan: {} artifacts.", artifactCount);
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while waiting for artifact result", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Determines the {@link MavenPomXmlDescriptor} of a POM {@link Artifact}.
     *
     * @param modelArtifact
     *            The {@link Artifact} representing the model.
     * @param snapshot
     *            <code>true</code> if the artifact represents a snapshot.
     * @param lastModified
     *            The last modified timestamp.
     * @param repositoryDescriptor
     *            The {@link MavenRepositoryDescriptor}.
     * @param effectiveModelBuilder
     *            The {@link PomModelBuilder}.
     * @param cache
     *            The {@link Cache}.
     * @return The {@link MavenPomXmlDescriptor} representing the model.
     */
    private MavenPomXmlDescriptor getModel(Artifact modelArtifact, boolean snapshot, long lastModified, MavenRepositoryDescriptor repositoryDescriptor,
            PomModelBuilder effectiveModelBuilder, Cache<String, MavenPomXmlDescriptor> cache) {
        AetherArtifactCoordinates modelCoordinates = new AetherArtifactCoordinates(modelArtifact);
        String fqn = MavenArtifactHelper.getId(modelCoordinates);
        return cache.get(fqn, key -> {
            MavenPomXmlDescriptor modelDescriptor = repositoryDescriptor.findModel(key);
            if (modelDescriptor == null) {
                scanner.getContext().push(PomModelBuilder.class, effectiveModelBuilder);
                try {
                    LOGGER.info("Scanning model '{}'.", modelArtifact);
                    modelDescriptor = scan(modelArtifact);
                } finally {
                    scanner.getContext().pop(PomModelBuilder.class);
                }
                markReleaseOrSnaphot(modelDescriptor, modelCoordinates, snapshot, lastModified);
                repositoryDescriptor.getContainedModels().add(modelDescriptor);
            }
            return modelDescriptor;
        });
    }

    /**
     * Scans the given {@link Artifact}.
     *
     * @param artifact
     *            The {@link Artifact}.
     * @param <D>
     *            The expected {@link Descriptor} type.
     * @return The {@link Descriptor}.
     */
    private <D extends Descriptor> D scan(Artifact artifact) {
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
     * @param descriptor
     *            the descriptor
     * @param coordinates
     *            The {@link Coordinates}.
     * @param snapshot
     *            if the artifact is a snapshot
     * @param lastModified
     *            last modified date
     * @return The {@link ArtifactInfoDescriptor}.
     */
    private <D extends MavenDescriptor> ArtifactInfoDescriptor markReleaseOrSnaphot(D descriptor, Coordinates coordinates, boolean snapshot,
            Long lastModified) {
        ArtifactInfoDescriptor artifactInfoDescriptor;
        Store store = scanner.getContext().getStore();
        if (snapshot) {
            artifactInfoDescriptor = store.addDescriptorType(descriptor, MavenSnapshotDescriptor.class);
        } else {
            artifactInfoDescriptor = store.addDescriptorType(descriptor, MavenReleaseDescriptor.class);
        }
        if (artifactInfoDescriptor.getFullQualifiedName() == null) {
            artifactInfoDescriptor.setFullQualifiedName(MavenArtifactHelper.getId(coordinates));
        }
        artifactInfoDescriptor.setLastModified(lastModified);
        return artifactInfoDescriptor;
    }
}
