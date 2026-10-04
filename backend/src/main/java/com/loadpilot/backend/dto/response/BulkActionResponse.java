package com.loadpilot.backend.dto.response;

import java.util.List;

/** Resultat d'une action groupee, element par element (succes partiels possibles). */
public record BulkActionResponse(List<Item> results, int succeeded, int failed) {

    public record Item(String id, String name, boolean success, String message) {
    }

    public static BulkActionResponse of(List<Item> results) {
        int ok = (int) results.stream().filter(Item::success).count();
        return new BulkActionResponse(results, ok, results.size() - ok);
    }
}
