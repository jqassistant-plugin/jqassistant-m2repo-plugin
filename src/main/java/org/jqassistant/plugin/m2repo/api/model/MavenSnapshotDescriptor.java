package org.jqassistant.plugin.m2repo.api.model;

import com.buschmais.jqassistant.core.store.api.model.FullQualifiedNameDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenDescriptor;
import com.buschmais.xo.neo4j.api.annotation.Label;

/**
 * Represents a snapshot artifact in a maven repository.
 *
 * @author pherklotz
 */
@Label(value = "Snapshot", usingIndexedPropertyOf = FullQualifiedNameDescriptor.class)
public interface MavenSnapshotDescriptor extends MavenDescriptor, ArtifactInfoDescriptor {
}
