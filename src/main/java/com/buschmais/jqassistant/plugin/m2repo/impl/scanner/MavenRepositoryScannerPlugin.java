package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactFilter;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.MavenRepositoryArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenRepositoryResolver;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A scanner for (remote) maven repositories.
 *
 * @author pherklotz
 */
public class MavenRepositoryScannerPlugin extends AbstractScannerPlugin<URL, MavenRepositoryDescriptor> {

    public static final String DEFAULT_M2REPO_DIR = "./jqassistant/data/m2repo";

    private static final String PROPERTY_NAME_ARTIFACTS_KEEP = "m2repo.artifacts.keep";
    private static final String PROPERTY_NAME_ARTIFACTS_SCAN = "m2repo.artifacts.scan";
    private static final String PROPERTY_NAME_FILTER_INCLUDES = "m2repo.filter.includes";
    private static final String PROPERTY_NAME_FILTER_EXCLUDES = "m2repo.filter.excludes";
    private static final String PROPERTY_NAME_DIRECTORY = "m2repo.directory";

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenRepositoryScannerPlugin.class);

    private boolean keepArtifacts;
    private boolean scanArtifacts;
    private ArtifactFilter artifactFilter;
    private File localDirectory;

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
        List<String> includeFilter = getFilterPattern(PROPERTY_NAME_FILTER_INCLUDES);
        List<String> excludeFilter = getFilterPattern(PROPERTY_NAME_FILTER_EXCLUDES);
        artifactFilter = new ArtifactFilter(includeFilter, excludeFilter);
        localDirectory = new File(getStringProperty(PROPERTY_NAME_DIRECTORY, DEFAULT_M2REPO_DIR));
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

    /** {@inheritDoc} */
    @Override
    public MavenRepositoryDescriptor scan(URL repositoryUrl, String path, Scope scope, Scanner scanner) throws IOException {
        if (!localDirectory.exists()) {
            LOGGER.info("Creating local maven repository directory {}", localDirectory.getAbsolutePath());
            localDirectory.mkdirs();
        }
        AetherArtifactProvider artifactProvider = new AetherArtifactProvider(repositoryUrl, localDirectory);
        ArtifactSearchResultScanner artifactSearchResultScanner = new ArtifactSearchResultScanner(scanner, artifactProvider, artifactFilter, scanArtifacts,
                keepArtifacts);

        MavenRepositoryDescriptor repositoryDescriptor = MavenRepositoryResolver.resolve(scanner.getContext().getStore(), repositoryUrl.toString());
        MavenRepositoryArtifactResolver repositoryArtifactResolver = new MavenRepositoryArtifactResolver(artifactProvider.getRepositoryRoot());
        try (MavenIndex mavenIndex = artifactProvider.getMavenIndex()) {
            Date lastScanTime = new Date(repositoryDescriptor.getLastUpdate());
            mavenIndex.updateIndex();
            // register file resolver strategy to identify repository artifacts
            scanner.getContext().push(ArtifactResolver.class, repositoryArtifactResolver);
            try (ArtifactSearchResult searchResult = mavenIndex.getArtifactsSince(lastScanTime)) {
                artifactSearchResultScanner.scan(searchResult, repositoryDescriptor);
            } finally {
                scanner.getContext().pop(ArtifactResolver.class);
            }
        }
        repositoryDescriptor.setLastUpdate(System.currentTimeMillis());
        return repositoryDescriptor;
    }

}
