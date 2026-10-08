package com.armaturemc.client;

import java.util.*;
import org.joml.Vector2f;
import org.joml.Vector3f;

/** Separates overlapping coplanar authored faces in bone-local space, once at bundle load. */
final class ClientSurfaceSeparation {
    static final float PLANE_EPSILON = 1e-6f;
    // Less than 1/256 of a Blockbench pixel; never accumulates across a large mesh.
    static final float MAX_OFFSET = 1f / 4096;
    private static final int MAX_COMPARISONS = 1_000_000;
    private ClientSurfaceSeparation() { }

    static List<ClientModel.Quad> resolve(List<ClientModel.Quad> quads) {
        Map<Normal, NavigableMap<Float, List<Face>>> planes = new HashMap<>();
        List<ClientModel.Quad> result = new ArrayList<>(quads.size());
        int comparisons = 0, highestRank = 0;
        for (var quad : quads) {
            if (quad.normal().lengthSquared() == 0) continue; // A zero-width cube's edge is not a surface.
            Face face = new Face(quad);
            var distances = planes.computeIfAbsent(face.normalKey, ignored -> new TreeMap<>());
            int rank = 0;
            for (var candidates : distances.subMap(face.distance - PLANE_EPSILON, true,
                    face.distance + PLANE_EPSILON, true).values()) {
                for (Face previous : candidates) {
                    if (++comparisons > MAX_COMPARISONS) {
                        // Bound work on oversized coplanar bundles. Conservatively
                        // assign a distinct priority instead of quadratic overlap tests.
                        rank = highestRank + 1;
                        break;
                    }
                    if (face.overlaps(previous)) rank = Math.max(rank, previous.rank + 1);
                }
                if (comparisons > MAX_COMPARISONS) break;
            }
            highestRank = Math.max(highestRank, rank);
            face.rank = rank;
            distances.computeIfAbsent(face.distance, ignored -> new ArrayList<>()).add(face);
            if (rank == 0) { result.add(quad); continue; }
            float offset = MAX_OFFSET * (rank / (rank + 1f));
            Vector3f delta = new Vector3f(quad.normal()).mul(offset);
            result.add(new ClientModel.Quad(quad.texture(), quad.vertices().stream().map(vertex ->
                new ClientModel.Vertex(vertex.x() + delta.x, vertex.y() + delta.y, vertex.z() + delta.z,
                    vertex.u(), vertex.v())).toList(), quad.normal()));
        }
        return List.copyOf(result);
    }

    private record Normal(int x, int y, int z) { }

    private static final class Face {
        final Normal normalKey;
        final float distance;
        final Vector2f[] polygon = new Vector2f[4];
        int rank;
        Face(ClientModel.Quad quad) {
            Vector3f normal = new Vector3f(quad.normal());
            int axis = Math.abs(normal.x) >= Math.abs(normal.y) && Math.abs(normal.x) >= Math.abs(normal.z) ? 0
                : Math.abs(normal.y) >= Math.abs(normal.z) ? 1 : 2;
            if (normal.get(axis) < 0) normal.negate();
            normalKey = new Normal(Math.round(normal.x * 10000), Math.round(normal.y * 10000), Math.round(normal.z * 10000));
            var first = quad.vertices().getFirst();
            distance = normal.dot(first.x(), first.y(), first.z());
            for (int i = 0; i < 4; i++) {
                var vertex = quad.vertices().get(i);
                polygon[i] = switch (axis) {
                    case 0 -> new Vector2f(vertex.y(), vertex.z());
                    case 1 -> new Vector2f(vertex.x(), vertex.z());
                    default -> new Vector2f(vertex.x(), vertex.y());
                };
            }
        }

        // Convex-quad separating-axis test. Faces that merely touch along an
        // edge stay untouched; rotated cubes retain their actual footprint.
        boolean overlaps(Face other) {
            return overlapOnEdges(polygon, polygon, other.polygon)
                && overlapOnEdges(other.polygon, polygon, other.polygon);
        }
        private static boolean overlapOnEdges(Vector2f[] edges, Vector2f[] left, Vector2f[] right) {
            for (int i = 0; i < 4; i++) {
                var a = edges[i]; var b = edges[(i + 1) % 4];
                float nx = a.y - b.y, ny = b.x - a.x;
                float length = (float)Math.sqrt(nx * nx + ny * ny);
                if (length == 0) continue;
                nx /= length; ny /= length;
                float minLeft = Float.POSITIVE_INFINITY, maxLeft = Float.NEGATIVE_INFINITY;
                float minRight = Float.POSITIVE_INFINITY, maxRight = Float.NEGATIVE_INFINITY;
                for (var vertex : left) {
                    float value = nx * vertex.x + ny * vertex.y;
                    minLeft = Math.min(minLeft, value); maxLeft = Math.max(maxLeft, value);
                }
                for (var vertex : right) {
                    float value = nx * vertex.x + ny * vertex.y;
                    minRight = Math.min(minRight, value); maxRight = Math.max(maxRight, value);
                }
                if (Math.min(maxLeft, maxRight) - Math.max(minLeft, minRight) <= PLANE_EPSILON) return false;
            }
            return true;
        }
    }
}
