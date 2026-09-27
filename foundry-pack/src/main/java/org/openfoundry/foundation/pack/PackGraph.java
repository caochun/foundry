package org.openfoundry.foundation.pack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PackGraph {
    private final Map<String, PackManifest> manifests = new LinkedHashMap<>();
    private final List<String> ordered = new ArrayList<>();
    private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();

    PackGraph(Collection<PackManifest> values) {
        var names = new HashSet<String>();
        for (var manifest : values.stream().sorted(java.util.Comparator.comparing(PackManifest::namespace)).toList()) {
            Version.parse(manifest.version());
            if (!names.add(manifest.name()) || manifests.putIfAbsent(manifest.namespace(), manifest) != null) throw new PackLoadException("Duplicate pack identity: " + manifest.name());
        }
        for (String namespace : manifests.keySet()) visit(namespace, new HashSet<>());
    }

    List<String> ordered() { return List.copyOf(ordered); }

    boolean visible(String owner, String referencedOwner) {
        return owner.equals(referencedOwner) || referencedOwner.equals("openfoundry.core") || dependencies.get(owner).contains(referencedOwner);
    }

    private Set<String> visit(String namespace, Set<String> visiting) {
        if (dependencies.containsKey(namespace)) return dependencies.get(namespace);
        if (!visiting.add(namespace)) throw new PackLoadException("Cyclic pack dependency: " + namespace);
        var result = new HashSet<String>();
        var manifest = manifests.get(namespace);
        for (String required : manifest.dependencies().keySet().stream().sorted().toList()) {
            var target = manifests.get(required);
            String constraint = manifest.dependencies().get(required).trim();
            if (target == null || !satisfies(target.version(), constraint)) throw new PackLoadException(namespace + " requires " + required + " " + constraint);
            result.add(required);
            result.addAll(visit(required, visiting));
        }
        visiting.remove(namespace);
        dependencies.put(namespace, Set.copyOf(result));
        ordered.add(namespace);
        return result;
    }

    private static boolean satisfies(String version, String constraint) {
        boolean atLeast = constraint.startsWith(">=");
        String expected = atLeast ? constraint.substring(2).trim() : constraint;
        int comparison = Version.parse(version).compareTo(Version.parse(expected));
        return atLeast ? comparison >= 0 : comparison == 0;
    }

    record Version(List<BigInteger> numbers, List<String> pre) implements Comparable<Version> {
        static Version parse(String value) {
            var pattern = java.util.regex.Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
            var match = pattern.matcher(value);
            if (!match.matches()) throw new PackLoadException("Unsupported version or dependency constraint: " + value);
            var pre = match.group(4) == null ? List.<String>of() : List.of(match.group(4).split("\\."));
            for (String part : pre) if (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")) throw new PackLoadException("Invalid prerelease version: " + value);
            return new Version(List.of(new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match.group(3))), pre);
        }

        @Override
        public int compareTo(Version other) {
            for (int i = 0; i < 3; i++) {
                int result = numbers.get(i).compareTo(other.numbers.get(i));
                if (result != 0) return result;
            }
            if (pre.isEmpty() || other.pre.isEmpty()) return pre.isEmpty() ? (other.pre.isEmpty() ? 0 : 1) : -1;
            for (int i = 0; i < Math.min(pre.size(), other.pre.size()); i++) {
                String a = pre.get(i), b = other.pre.get(i);
                boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
                int result = an && bn ? new BigInteger(a).compareTo(new BigInteger(b)) : an != bn ? (an ? -1 : 1) : a.compareTo(b);
                if (result != 0) return result;
            }
            return Integer.compare(pre.size(), other.pre.size());
        }
    }
}
