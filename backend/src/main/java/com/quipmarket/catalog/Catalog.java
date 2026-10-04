package com.quipmarket.catalog;

import com.quipmarket.catalog.internal.EquipmentRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The catalog module's public facade. Other modules call this, never the repository,
 * so the catalog can change its storage without breaking them.
 */
@Service
public class Catalog {

    private final EquipmentRepository repository;

    Catalog(EquipmentRepository repository) {
        this.repository = repository;
    }

    public List<Equipment> search(EquipmentFilter filter) {
        return repository.search(filter == null ? EquipmentFilter.NONE : filter);
    }

    public Optional<Equipment> findById(String id) {
        return repository.findById(id);
    }

    /** Who receives the sale proceeds for this listing. */
    public String sellerOf(String equipmentId) {
        return repository.sellerOf(equipmentId);
    }

    /** Batch lookup used by GraphQL @BatchMapping (one query for N ids). */
    public Map<String, Equipment> findByIds(Collection<String> ids) {
        return repository.findByIds(ids);
    }
}
