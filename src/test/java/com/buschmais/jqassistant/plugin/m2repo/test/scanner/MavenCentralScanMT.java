package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

import org.junit.jupiter.api.Test;

public class MavenCentralScanMT extends AbstractMavenRepositoryTest {

    /**
     * URL of M2 central mirror provided by Sonatype Nexus 3
     */
    public static final String MAVEN_CENTRAL = "http://localhost:8081/repository/maven-central/";

    @Test
    public void scanModelOnly() throws IOException {
        store.beginTransaction();
        Map<String, Object> scannerProperties = getScannerProperties();
        scannerProperties.put("m2repo.artifacts.scan", "false");
        getScanner(scannerProperties).scan(new URL(MAVEN_CENTRAL), MAVEN_CENTRAL, MavenScope.REPOSITORY);
        store.commitTransaction();
    }

}
