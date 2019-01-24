package com.buschmais.jqassistant.plugin.m2repo.api.model;

import com.buschmais.jqassistant.plugin.maven3.api.model.MavenDescriptor;
import com.buschmais.xo.neo4j.api.annotation.Label;

/**
 * Represents a snapshot artifact in a maven repository.
 * 
 * @author pherklotz
 */
@Label(value = "Snapshot")
public interface MavenSnapshotDescriptor extends MavenDescriptor, LastModifiedDescriptor {
}
