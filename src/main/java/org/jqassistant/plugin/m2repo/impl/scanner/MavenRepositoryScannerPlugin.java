package org.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Date;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.common.api.scanner.FileResolver;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactFilter;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.MavenRepositoryArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenRepositoryResolver;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

/**
 * A scanner for (remote) maven repositories.
 *
 * @author pherklotz
 */
public class MavenRepositoryScannerPlugin extends AbstractScannerPlugin<URL, MavenRepositoryDescriptor> {

    private static final String PROPERTY_NAME_ARTIFACTS_KEEP = "m2repo.artifacts.keep";
    private static final String PROPERTY_NAME_ARTIFACTS_SCAN = "m2repo.artifacts.scan";
    private static final String PROPERTY_NAME_FILTER_INCLUDES = "m2repo.filter.includes";
    private static final String PROPERTY_NAME_FILTER_EXCLUDES = "m2repo.filter.excludes";
    private static final String PROPERTY_NAME_DIRECTORY = "m2repo.directory";
    private static final String DEFAULT_DATA_DIRECTORY = "m2repo";

    private boolean keepArtifacts;
    private boolean scanArtifacts;
    private ArtifactFilter artifactFilter;
    private String localDirectoryName;

    /** {@inheritDoc} */
    @Override
    public boolean accepts(URL item, String path, Scope scope) {
        return MavenScope.REPOSITORY == scope;
    }

    /** {@inheritDoc} */
    @Override
    public void configure() {
        scanArtifacts = getBooleanProperty(PROPERTY_NAME_ARTIFACTS_SCAN, false);
        keepArtifacts = getBooleanProperty(PROPERTY_NAME_ARTIFACTS_KEEP, true);
        artifactFilter = new ArtifactFilter(getStringProperty(PROPERTY_NAME_FILTER_INCLUDES, null), getStringProperty(PROPERTY_NAME_FILTER_EXCLUDES, null));
        localDirectoryName = getStringProperty(PROPERTY_NAME_DIRECTORY, null);
    }

    /** {@inheritDoc} */
    @Override
    public MavenRepositoryDescriptor scan(URL repositoryUrl, String path, Scope scope, Scanner scanner) throws IOException {
        ScannerContext context = scanner.getContext();
        File localDirectory = getLocalDirectory(context);
        AetherArtifactProvider artifactProvider = new AetherArtifactProvider(repositoryUrl, localDirectory);
        ArtifactSearchResultScanner artifactSearchResultScanner = new ArtifactSearchResultScanner(scanner, artifactProvider, artifactFilter, scanArtifacts,
                keepArtifacts);

        MavenRepositoryDescriptor repositoryDescriptor = MavenRepositoryResolver.resolve(context.getStore(), repositoryUrl.toString());
        FileResolver fileResolver = context.peek(FileResolver.class);
        MavenRepositoryArtifactResolver repositoryArtifactResolver = new MavenRepositoryArtifactResolver(artifactProvider.getRepositoryRoot(), fileResolver);
        try (MavenIndex mavenIndex = artifactProvider.getMavenIndex()) {
            Date lastScanTime = new Date(repositoryDescriptor.getLastUpdate());
            mavenIndex.updateIndex();
            // register file resolver strategy to identify repository artifacts
            context.push(ArtifactResolver.class, repositoryArtifactResolver);
            try (ArtifactSearchResult searchResult = mavenIndex.getArtifactsSince(lastScanTime)) {
                artifactSearchResultScanner.scan(searchResult, repositoryDescriptor);
            } finally {
                context.pop(ArtifactResolver.class);
            }
        }
        repositoryDescriptor.setLastUpdate(System.currentTimeMillis());
        return repositoryDescriptor;
    }

    private File getLocalDirectory(ScannerContext context) {
        File localDirectory;
        if (localDirectoryName != null) {
            localDirectory = Paths.get(localDirectoryName)
                .toAbsolutePath()
                .normalize()
                .toFile();
            localDirectory.mkdirs();
        } else {
            localDirectory = context.getDataDirectory(DEFAULT_DATA_DIRECTORY);
        }
        return localDirectory;
    }
}
