package com.quipmarket.catalog.internal;

import com.quipmarket.catalog.Catalog;
import com.quipmarket.catalog.Equipment;
import com.quipmarket.catalog.EquipmentFilter;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class CatalogGraphQlController {

    private final Catalog catalog;

    CatalogGraphQlController(Catalog catalog) {
        this.catalog = catalog;
    }

    @QueryMapping
    List<Equipment> equipment(@Argument EquipmentFilter filter) {
        return catalog.search(filter);
    }

    @QueryMapping
    Equipment equipmentById(@Argument String id) {
        return catalog.findById(id).orElse(null);
    }
}
