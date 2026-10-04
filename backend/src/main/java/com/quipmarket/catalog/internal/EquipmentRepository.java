package com.quipmarket.catalog.internal;

import com.quipmarket.catalog.Equipment;
import com.quipmarket.catalog.Equipment.Category;
import com.quipmarket.catalog.Equipment.ListingType;
import com.quipmarket.catalog.Equipment.RentalRates;
import com.quipmarket.catalog.Equipment.Spec;
import com.quipmarket.catalog.EquipmentFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Plain SQL with named parameters. Every user value is a bind parameter (no SQL injection). */
@Repository
public class EquipmentRepository {

    private static final String SELECT = """
            SELECT id, title, category, make, model, year, hours, location, listing_type,
                   sale_price_cents, daily_rate_cents, weekly_rate_cents, monthly_rate_cents,
                   description, specs::text AS specs
            FROM equipment
            """;
    private static final TypeReference<List<Spec>> SPECS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    EquipmentRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Equipment> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public String sellerOf(String id) {
        return jdbc.sql("SELECT seller_id FROM equipment WHERE id = :id").param("id", id).query(String.class).single();
    }

    public Map<String, Equipment> findByIds(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql(SELECT + " WHERE id IN (:ids)").param("ids", ids).query(this::map).list().stream()
                .collect(Collectors.toMap(Equipment::id, Function.identity()));
    }

    public List<Equipment> search(EquipmentFilter f) {
        var where = new ArrayList<String>();
        var params = new HashMap<String, Object>();

        if (f.search() != null && !f.search().isBlank()) {
            // Word-PREFIX match, same rule as the frontend: "cat" matches Caterpillar, not Bobcat.
            // Terms are reduced to [a-z0-9] so they are safe inside a regex. \m = start of word.
            String[] terms = Arrays.stream(f.search().toLowerCase().split("[^a-z0-9]+")).filter(t -> !t.isEmpty()).toArray(String[]::new);
            for (int i = 0; i < terms.length; i++) {
                where.add("(title || ' ' || make || ' ' || model || ' ' || replace(category, '_', ' ')) ~* :term" + i);
                params.put("term" + i, "\\m" + terms[i]);
            }
        }
        if (f.categories() != null && !f.categories().isEmpty()) {
            where.add("category IN (:categories)");
            params.put("categories", f.categories().stream().map(Enum::name).toList());
        }
        if (f.listing() == EquipmentFilter.ListingFilter.SALE) where.add("listing_type <> 'RENT'");
        if (f.listing() == EquipmentFilter.ListingFilter.RENT) where.add("listing_type <> 'SALE'");
        if (f.maxSalePriceCents() != null) {
            // Rent-only machines have no sale price and pass this filter (same as the frontend).
            where.add("(sale_price_cents IS NULL OR sale_price_cents <= :maxPrice)");
            params.put("maxPrice", f.maxSalePriceCents());
        }
        if (f.minYear() != null) {
            where.add("year >= :minYear");
            params.put("minYear", f.minYear());
        }
        if (f.maxHours() != null) {
            where.add("hours <= :maxHours");
            params.put("maxHours", f.maxHours());
        }
        if (f.location() != null && !f.location().isBlank()) {
            where.add("location ILIKE :location");
            params.put("location", "%" + f.location().replace("%", "\\%").replace("_", "\\_") + "%");
        }

        String orderBy = switch (f.sort() == null ? EquipmentFilter.Sort.NEWEST : f.sort()) {
            case NEWEST -> "year DESC, hours ASC";
            case PRICE_ASC -> "COALESCE(sale_price_cents, monthly_rate_cents) ASC";
            case PRICE_DESC -> "COALESCE(sale_price_cents, monthly_rate_cents) DESC";
            case HOURS_ASC -> "hours ASC";
        };

        String sql = SELECT + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY " + orderBy + ", id";
        return jdbc.sql(sql).params(params).query(this::map).list();
    }

    private Equipment map(ResultSet rs, int row) throws SQLException {
        Long daily = (Long) rs.getObject("daily_rate_cents");
        RentalRates rates = daily == null
                ? null
                : new RentalRates(daily, rs.getLong("weekly_rate_cents"), rs.getLong("monthly_rate_cents"));
        return new Equipment(
                rs.getString("id"),
                rs.getString("title"),
                Category.valueOf(rs.getString("category")),
                rs.getString("make"),
                rs.getString("model"),
                rs.getInt("year"),
                rs.getInt("hours"),
                rs.getString("location"),
                ListingType.valueOf(rs.getString("listing_type")),
                (Long) rs.getObject("sale_price_cents"),
                rates,
                rs.getString("description"),
                json.readValue(rs.getString("specs"), SPECS));
    }
}
