package dev.stargenesis.integrations;

import java.util.*;

/**
 * Copied recipe metadata, never an executable cooking plan or inventory authorization.
 * A provider may inherit its caller's pinned read context. No live generation or freshness
 * token is implied; published edits are observed by subsequent top-level requests.
 */
public interface RecipeCatalog extends IntegrationAdapter {
    int MAX_RECIPES = 2048;
    int MAX_MEMBERS = 128;
    String integrationId();
    Summary summary();
    List<String> list(Appliance appliance, Family family);
    Optional<Definition> inspect(Key key);

    enum Appliance { POT, STEAMER, STOCKPOT, SHAWARMA, MILLSTONE, CHOPPING_BOARD, TEAPOT, BAMBOO_TRAY }
    enum Family { ACCURATE, FLEX, POT, STOCKPOT, CHOPPING, TEAPOT, BAMBOO_TRAY }

    record Key(Appliance appliance, Family family, String id) {
        public Key {
            Objects.requireNonNull(appliance); Objects.requireNonNull(family);
            id = ItemCatalog.requireId(id);
            Appliance fixed = switch (family) {
                case POT -> Appliance.POT;
                case STOCKPOT -> Appliance.STOCKPOT;
                case CHOPPING -> Appliance.CHOPPING_BOARD;
                case TEAPOT -> Appliance.TEAPOT;
                case BAMBOO_TRAY -> Appliance.BAMBOO_TRAY;
                default -> null;
            };
            if (fixed != null && fixed != appliance) throw new IllegalArgumentException("Recipe appliance/family mismatch");
        }
    }

    record Summary(int total, Map<Appliance, Integer> byAppliance) {
        public Summary {
            if (total < 0 || total > MAX_RECIPES || byAppliance == null
                    || !byAppliance.keySet().equals(EnumSet.allOf(Appliance.class)))
                throw new IllegalArgumentException("Invalid recipe summary or recipe budget exceeded");
            long sum = 0;
            for (Integer count : byAppliance.values()) {
                if (count == null || count < 0 || count > MAX_RECIPES) throw new IllegalArgumentException("Invalid appliance count");
                sum += count;
            }
            if (sum != total) throw new IllegalArgumentException("Recipe counts disagree");
            byAppliance = Collections.unmodifiableMap(new EnumMap<>(byAppliance));
        }
    }

    /**
     * Family-specific field names follow the audited provider records. Lists keep order,
     * including repeated weighted outputs. Maps, lists and nullable carriers are deeply copied.
     * Only String, Integer, Double, Boolean, null, string-keyed maps and lists are permitted.
     */
    record Definition(Key key, Map<String, Object> fields) {
        public Definition {
            Objects.requireNonNull(key);
            fields = new CopyBudget().map(Objects.requireNonNull(fields), 0);
        }
    }

    final class CopyBudget {
        private int nodes, characters;
        private Object copy(Object value, int depth) {
            if (++nodes > 2048 || depth > 8) throw new IllegalArgumentException("Recipe metadata node/depth budget exceeded");
            if (value == null || value instanceof Integer || value instanceof Boolean) return value;
            if (value instanceof Double number && Double.isFinite(number)) return number;
            if (value instanceof String text) return text(text);
            if (value instanceof Map<?, ?> map) return map(map, depth);
            if (value instanceof List<?> list) {
                if (list.size() > MAX_MEMBERS) throw new IllegalArgumentException("Recipe list budget exceeded");
                List<Object> result = new ArrayList<>(list.size());
                for (Object item : list) result.add(copy(item, depth + 1));
                return Collections.unmodifiableList(result);
            }
            throw new IllegalArgumentException("Unsupported recipe metadata type");
        }
        private String text(String text) {
            characters += text.length();
            if (text.length() > 1024 || characters > 32768) throw new IllegalArgumentException("Recipe text budget exceeded");
            return text;
        }
        private Map<String, Object> map(Map<?, ?> source, int depth) {
            if (source.size() > MAX_MEMBERS) throw new IllegalArgumentException("Recipe map budget exceeded");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Recipe field key must be text");
                result.put(text(key), copy(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(result);
        }
    }
}
