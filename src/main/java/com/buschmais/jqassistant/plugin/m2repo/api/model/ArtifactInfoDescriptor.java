package com.buschmais.jqassistant.plugin.m2repo.api.model;

import com.buschmais.jqassistant.core.store.api.model.FullQualifiedNameDescriptor;

public interface ArtifactInfoDescriptor extends FullQualifiedNameDescriptor {

    Long getLastModified();

    void setLastModified(Long lastModified);

}
