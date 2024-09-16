package org.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.api.model.ArtifactDescriptor;
import com.buschmais.jqassistant.plugin.common.api.model.DependsOnDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.*;
import com.buschmais.jqassistant.plugin.maven3.api.model.*;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.PomModelBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.index.ArtifactInfo;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jqassistant.plugin.m2repo.api.ArtifactProvider;
import org.jqassistant.plugin.m2repo.api.model.ArtifactInfoDescriptor;
import org.jqassistant.plugin.m2repo.api.model.MavenReleaseDescriptor;
import org.jqassistant.plugin.m2repo.api.model.MavenSnapshotDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofMillis;

/**
 * Processes an {@link ArtifactSearchResult}.
 */
public class ArtifactSearchResultScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactSearchResultScanner.class);

    private static final String EXTENSION_POM = "pom";
    private static final int QUEUE_CAPACITY = 500;

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

    /**
     * Resolves, scans and add the artifact to the
     * {@link MavenRepositoryDescriptor}.
     *
     * @param artifactSearchResult
     *     the {@link ArtifactSearchResult}
     * @param repositoryDescriptor
     *     The {@link MavenRepositoryDescriptor}.
     * @throws IOException
     *     When an artifact result could not be retrieved.
     */
    public void scan(ArtifactSearchResult artifactSearchResult, MavenRepositoryDescriptor repositoryDescriptor) throws IOException {
        PomModelBuilder effectiveModelBuilder = new EffectiveModelBuilder(artifactProvider);
        GAVResolver gavResolver = new GAVResolver(scanner.getContext()
            .getStore(), repositoryDescriptor);

        BlockingQueue<ArtifactTask.Result> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> new Thread(r, ArtifactTask.class.getSimpleName()));
        Future<?> future = pool.submit(new ArtifactTask(artifactSearchResult, artifactFilter, scanArtifacts, queue, artifactProvider));

        LOGGER.info("Starting scan.");
        StopWatch stopwatch = StopWatch.createStarted();
        try {
            long artifactCount = consume(artifactSearchResult, queue, effectiveModelBuilder, gavResolver, repositoryDescriptor, stopwatch);
            // force potential exception from task to be thrown
            future.get();
            LOGGER.info("Finished scan: {} artifacts (duration: {}).", artifactCount, ofMillis(stopwatch.getTime()));
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for artifact result", e);
            Thread.currentThread()
                .interrupt();
        } catch (ExecutionException e) {
            throw new IOException("Artifact task returned reported a problem.", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private long consume(ArtifactSearchResult artifactSearchResult, BlockingQueue<ArtifactTask.Result> queue, PomModelBuilder effectiveModelBuilder,
        GAVResolver gavResolver, MavenRepositoryDescriptor repositoryDescriptor, StopWatch stopwatch) throws InterruptedException {
        Cache<String, MavenPomXmlDescriptor> cache = Caffeine.newBuilder()
            .maximumSize(256)
            .build();
        ArtifactTask.Result result;
        long artifactCount = 0;
        while ((result = queue.take()) != ArtifactTask.Result.LAST) {
            ArtifactInfo artifactInfo = result.getArtifactInfo();
            Coordinates artifactCoordinates = new ArtifactInfoCoordinates(artifactInfo);
            LOGGER.debug("Processing '{}'.", artifactInfo);
            long lastModified = artifactInfo.getLastModified();
            boolean snapshot = MavenArtifactHelper.isSnapshot(artifactCoordinates);
            Optional<ArtifactResult> modelArtifactResult = result.getModelArtifactResult();
            MavenPomXmlDescriptor modelDescriptor = null;
            if (modelArtifactResult.isPresent()) {
                Artifact modelArtifact = modelArtifactResult.get()
                    .getArtifact();
                modelDescriptor = getModel(modelArtifact, snapshot, lastModified, repositoryDescriptor, effectiveModelBuilder, cache);
            } else {
                LOGGER.warn("No model found for {}.", artifactInfo);
            }
            // Skip if the POM itself is the artifact
            if (!EXTENSION_POM.equals(artifactInfo.getPackaging())) { // Note: packaging can be null
                MavenArtifactDescriptor mavenArtifactDescriptor = repositoryDescriptor.findArtifact(MavenArtifactHelper.getId(artifactCoordinates));
                if (mavenArtifactDescriptor == null) {
                    mavenArtifactDescriptor = getArtifact(artifactCoordinates, result.getArtifactResult(), snapshot, lastModified);
                    if (modelDescriptor != null) {
                        // Add DESCRIBES relation from model to artifact if it does not exist yet (e.g. due to an invalid model)
                        modelDescriptor.getDescribes()
                            .add(mavenArtifactDescriptor);
                        propagateDependencies(modelDescriptor, mavenArtifactDescriptor);
                    }
                    repositoryDescriptor.addArtifact(mavenArtifactDescriptor);
                    gavResolver.resolve(artifactCoordinates)
                        .getArtifacts()
                        .add(mavenArtifactDescriptor);
                }
            }
            artifactCount++;
            if (artifactCount % 500 == 0) {
                LOGGER.info("Processed {}/{} artifacts (duration: {}).", artifactCount, artifactSearchResult.getSize(), ofMillis(stopwatch.getTime()));
                scanner.getContext()
                    .getStore()
                    .flush();
            }
        }
        return artifactCount;
    }

    /**
     * Propagates declared dependencies by an effective model to the main artifact (i.e. same packaging and classifier).
     *
     * @param model
     *     The {@link MavenPomXmlDescriptor}.
     * @param artifact
     *     The {@link MavenArtifactDescriptor}.
     */
    private void propagateDependencies(MavenPomXmlDescriptor model, MavenArtifactDescriptor artifact) {
        if (model instanceof EffectiveDescriptor && Objects.equals(model.getPackaging(), artifact.getType()) && Objects.equals(model.getClassifier(),
            artifact.getClassifier())) {
            for (MavenDependencyDescriptor declaresDependency : model.getDeclaresDependencies()) {
                DependsOnDescriptor dependsOnDescriptor = scanner.getContext()
                    .getStore()
                    .create(artifact, DependsOnDescriptor.class, declaresDependency.getToArtifact());
                dependsOnDescriptor.setScope(declaresDependency.getScope());
                dependsOnDescriptor.setOptional(declaresDependency.isOptional());
            }
        }
    }

    /**
     * Determines the {@link MavenPomXmlDescriptor} of a POM {@link Artifact}.
     *
     * @param modelArtifact
     *     The {@link Artifact} representing the model.
     * @param snapshot
     *     <code>true</code> if the artifact represents a snapshot.
     * @param lastModified
     *     The last modified timestamp.
     * @param repositoryDescriptor
     *     The {@link MavenRepositoryDescriptor}.
     * @param effectiveModelBuilder
     *     The {@link PomModelBuilder}.
     * @param cache
     *     The {@link Cache}.
     * @return The {@link MavenPomXmlDescriptor} representing the model.
     */
    private MavenPomXmlDescriptor getModel(Artifact modelArtifact, boolean snapshot, long lastModified, MavenRepositoryDescriptor repositoryDescriptor,
        PomModelBuilder effectiveModelBuilder, Cache<String, MavenPomXmlDescriptor> cache) {
        AetherArtifactCoordinates modelCoordinates = new AetherArtifactCoordinates(modelArtifact);
        String fqn = MavenArtifactHelper.getId(modelCoordinates);
        return cache.get(fqn, key -> {
            MavenPomXmlDescriptor modelDescriptor = snapshot ? repositoryDescriptor.findSnapshotModel(key) : repositoryDescriptor.findReleaseModel(key);
            if (modelDescriptor == null) {
                scanner.getContext()
                    .push(PomModelBuilder.class, effectiveModelBuilder);
                try {
                    LOGGER.info("Scanning model '{}'.", modelArtifact);
                    modelDescriptor = scan(modelArtifact);
                } finally {
                    scanner.getContext()
                        .pop(PomModelBuilder.class);
                }
                markReleaseOrSnaphot(modelDescriptor, modelCoordinates, snapshot, lastModified);
                repositoryDescriptor.addModel(modelDescriptor);
            }
            return modelDescriptor;
        });
    }

    /**
     * Get the artifact for the given {@link Coordinates}.
     *
     * @param artifactCoordinates
     *     The {@link Coordinates}.
     * @param artifactResult
     *     The optional {@link ArtifactResult}.
     * @param snapshot
     *     <code>true</code> if the artifact represents a snapshot.
     * @param lastModified
     *     The last modified timestamp.
     * @return The {@link MavenArtifactDescriptor}.
     */
    private MavenArtifactDescriptor getArtifact(Coordinates artifactCoordinates, Optional<ArtifactResult> artifactResult, boolean snapshot, long lastModified) {
        MavenArtifactDescriptor mavenArtifactDescriptor;
        if (artifactResult.isPresent()) {
            // Scan artifact from repository
            Artifact artifact = artifactResult.get()
                .getArtifact();
            LOGGER.info("Scanning artifact '{}'.", artifact);
            ArtifactDescriptor descriptor = scan(artifact);
            mavenArtifactDescriptor = scanner.getContext()
                .getStore()
                .addDescriptorType(descriptor, MavenArtifactDescriptor.class);
            MavenArtifactHelper.setCoordinates(mavenArtifactDescriptor, artifactCoordinates);
        } else {
            // Resolve artifact without scanning
            mavenArtifactDescriptor = scanner.getContext()
                .peek(ArtifactResolver.class)
                .resolve(artifactCoordinates, scanner.getContext());
        }
        markReleaseOrSnaphot(mavenArtifactDescriptor, artifactCoordinates, snapshot, lastModified);
        return mavenArtifactDescriptor;
    }

    /**
     * Scans the given {@link Artifact}.
     *
     * @param artifact
     *     The {@link Artifact}.
     * @param <D>
     *     The expected {@link Descriptor} type.
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
     *     the descriptor
     * @param coordinates
     *     The {@link Coordinates}.
     * @param snapshot
     *     if the artifact is a snapshot
     * @param lastModified
     *     last modified date
     */
    private <D extends MavenDescriptor> void markReleaseOrSnaphot(D descriptor, Coordinates coordinates, boolean snapshot, Long lastModified) {
        ArtifactInfoDescriptor artifactInfoDescriptor;
        Store store = scanner.getContext()
            .getStore();
        if (snapshot) {
            artifactInfoDescriptor = store.addDescriptorType(descriptor, MavenSnapshotDescriptor.class);
        } else {
            artifactInfoDescriptor = store.addDescriptorType(descriptor, MavenReleaseDescriptor.class);
        }
        if (artifactInfoDescriptor.getFullQualifiedName() == null) {
            artifactInfoDescriptor.setFullQualifiedName(MavenArtifactHelper.getId(coordinates));
        }
        artifactInfoDescriptor.setLastModified(lastModified);
    }
}
