package org.jqassistant.plugin.m2repo.test.scanner;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

import org.junit.jupiter.api.Test;

class MavenCentralScanMT extends AbstractMavenRepositoryIT {

    /**
     * URL of M2 central mirror provided by Sonatype Nexus 3
     */
    public static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    @Override
    protected Map<String, Object> getScannerProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("m2repo.directory", ".cache/m2");
        properties.put("m2repo.filter.includes", "jakarta.*:*:*");
        return properties;
    }

    @Test
    void scanModelOnly() throws IOException {
        store.beginTransaction();
        getScanner().scan(new URL(MAVEN_CENTRAL), MAVEN_CENTRAL, MavenScope.REPOSITORY);
        store.commitTransaction();
    }

}
