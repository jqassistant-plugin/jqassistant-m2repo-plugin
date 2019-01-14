package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.util.Date;
import java.util.HashMap;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.AetherArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResult;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.MavenIndex;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.MavenRepositoryScannerPlugin;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

import org.junit.jupiter.api.Test;

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class MavenRepositoryScannerTest {

    @Test
    public void testMockMavenRepoScanner() throws Exception {
        MavenIndex mavenIndex = mock(MavenIndex.class);
        ArtifactSearchResult artifactSearchResult = mock(ArtifactSearchResult.class);
        when(mavenIndex.getArtifactsSince(new Date(0))).thenReturn(artifactSearchResult);

        AetherArtifactProvider artifactProvider = mock(AetherArtifactProvider.class);
        when(artifactProvider.getMavenIndex()).thenReturn(mavenIndex);

        Store store = mock(Store.class);
        ScannerContext context = mock(ScannerContext.class);
        when(context.getStore()).thenReturn(store);
        Scanner scanner = mock(Scanner.class);
        when(scanner.getContext()).thenReturn(context);

        MavenRepositoryDescriptor repoDescriptor = mock(MavenRepositoryDescriptor.class);
        when(artifactProvider.getRepositoryDescriptor()).thenReturn(repoDescriptor);

        MavenRepositoryScannerPlugin plugin = new MavenRepositoryScannerPlugin();
        plugin.configure(context, new HashMap<>());
        plugin.scan(artifactProvider, scanner);

        verify(mavenIndex).updateIndex();
        verify(scanner).scan(eq(artifactSearchResult), anyString(), eq(MavenScope.REPOSITORY));
    }
}
