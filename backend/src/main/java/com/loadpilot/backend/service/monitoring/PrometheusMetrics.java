package com.loadpilot.backend.service.monitoring;

import java.util.HashMap;
import java.util.Map;

/**
 * Lecture PURE du format texte d'exposition Prometheus : valeur de chaque metrique, les series
 * d'un meme nom (labels differents) etant additionnees (ex : jvm_memory_used_bytes par zone).
 *
 * Metriques standards du PROCESSUS (prom-client, client Java, node_exporter process...) :
 *   process_cpu_seconds_total      temps CPU cumule (compteur, en secondes) ;
 *   process_resident_memory_bytes  memoire residente (RSS).
 * Replis : process_cpu_user_seconds_total + process_cpu_system_seconds_total ; Micrometer
 * process_cpu_usage (0..1 de la machine) x system_cpu_count ; memoire jvm_memory_used_bytes ou
 * nodejs_heap_size_used_bytes.
 */
public final class PrometheusMetrics {

    private final Map<String, Double> values;

    private PrometheusMetrics(Map<String, Double> values) {
        this.values = values;
    }

    public static PrometheusMetrics parse(String text) {
        Map<String, Double> values = new HashMap<>();
        if (text == null) return new PrometheusMetrics(values);
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int nameEnd = 0;
            while (nameEnd < line.length() && isNameChar(line.charAt(nameEnd))) nameEnd++;
            if (nameEnd == 0) continue;
            String name = line.substring(0, nameEnd);
            int pos = nameEnd;
            if (pos < line.length() && line.charAt(pos) == '{') {
                pos = closingBrace(line, pos);
                if (pos < 0) continue;
                pos++;
            }
            String[] rest = line.substring(pos).trim().split("\\s+");
            if (rest.length == 0 || rest[0].isEmpty()) continue;
            Double value = number(rest[0]);
            if (value != null) values.merge(name, value, Double::sum);
        }
        return new PrometheusMetrics(values);
    }

    public Double get(String name) {
        return values.get(name);
    }

    /** Temps CPU cumule du processus en secondes, ou null. */
    public Double cpuSeconds() {
        Double total = values.get("process_cpu_seconds_total");
        if (total != null) return total;
        Double user = values.get("process_cpu_user_seconds_total");
        Double system = values.get("process_cpu_system_seconds_total");
        return user != null && system != null ? user + system : null;
    }

    /** CPU instantane en % d'un coeur si l'endpoint l'expose directement (Micrometer), sinon null. */
    public Double instantCpuPercent() {
        Double usage = values.get("process_cpu_usage");
        if (usage == null) return null;
        Double cores = values.get("system_cpu_count");
        return usage * (cores == null || cores <= 0 ? 1 : cores) * 100;
    }

    /** Nom de la metrique memoire retenue, ou null si aucune n'est exposee. */
    public String memoryMetric() {
        for (String name : new String[] {"process_resident_memory_bytes", "jvm_memory_used_bytes", "nodejs_heap_size_used_bytes"}) {
            if (values.containsKey(name)) return name;
        }
        return null;
    }

    public Double memoryBytes() {
        String metric = memoryMetric();
        return metric == null ? null : values.get(metric);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':';
    }

    /** Position de l'accolade fermante des labels (les valeurs entre guillemets peuvent contenir } ou \"). */
    private static int closingBrace(String line, int open) {
        boolean quoted = false;
        for (int i = open + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == '}' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static Double number(String s) {
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
