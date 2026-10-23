package com.akhester.auditui.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * What must be delivered (SOR Annex 2, section II) against what this build has. The list of requirements comes from the ChecklistA board.
 * The status is not copied from the board: a requirement whose screen routes do not exist in this build shows "Not built yet", and only
 * requirements checked by the screen group that wrote this page are called checked. Everything else says "not checked here".
 */
@Service
public class ChecklistService {

    public enum Kind { BUILT, DEMO, PARTIAL, PLANNED, NOTUI, TASK }

    /** n: item number in messages (checklist.i{n}.*). routes: request mappings this requirement needs to exist. */
    public record Item(int n, boolean hasSub, Kind kind, boolean checked, String[] routes) {}

    /** status: built | builtunchecked | demo | partial | planned | notui | task | notyet. */
    public record Line(Item item, String status, List<String> missing) {}

    public static final List<Item> ITEMS = List.of(
        new Item(1, false, Kind.BUILT, true, new String[]{"/", "/find", "/patient/{id}"}),
        new Item(2, true, Kind.PARTIAL, false, new String[]{"/"}),
        new Item(3, false, Kind.PARTIAL, false, new String[]{"/alerts"}),
        new Item(4, false, Kind.DEMO, true, new String[]{"/patient/{id}"}),
        new Item(5, true, Kind.BUILT, false, new String[]{"/break-glass", "/reviews"}),
        new Item(6, false, Kind.BUILT, true, new String[]{"/audit/detail/{id}"}),
        new Item(7, false, Kind.BUILT, false, new String[]{"/overview"}),
        new Item(8, false, Kind.PARTIAL, true, new String[]{"/reports", "/reports/download"}),
        new Item(9, false, Kind.BUILT, false, new String[]{"/health"}),
        new Item(10, true, Kind.PLANNED, false, new String[]{}),
        new Item(11, false, Kind.NOTUI, false, new String[]{}),
        new Item(12, false, Kind.PLANNED, false, new String[]{"/patient/{id}"}),
        new Item(13, false, Kind.BUILT, false, new String[]{"/health", "/audit/verify-chain"}),
        new Item(14, false, Kind.PLANNED, false, new String[]{}),
        new Item(15, true, Kind.PARTIAL, false, new String[]{"/"}),
        new Item(16, true, Kind.PARTIAL, true, new String[]{"/patient/{id}"}),
        new Item(17, false, Kind.PARTIAL, false, new String[]{}),
        new Item(18, false, Kind.PARTIAL, false, new String[]{"/settings"}),
        new Item(19, false, Kind.PARTIAL, true, new String[]{"/audit/export.csv", "/reports/download"}),
        new Item(20, true, Kind.PARTIAL, true, new String[]{}),
        new Item(21, false, Kind.PARTIAL, true, new String[]{}),
        new Item(22, true, Kind.PARTIAL, false, new String[]{}),
        new Item(23, false, Kind.PARTIAL, false, new String[]{"/reviews"}),
        new Item(24, false, Kind.PARTIAL, false, new String[]{"/login"}),
        new Item(25, false, Kind.DEMO, true, new String[]{"/patient/{id}"}),
        new Item(26, false, Kind.TASK, false, new String[]{}),
        new Item(27, true, Kind.PLANNED, true, new String[]{"/admin"}),
        new Item(28, false, Kind.BUILT, true, new String[]{"/admin"}));

    private final RequestMappingHandlerMapping mapping;

    public ChecklistService(@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) { this.mapping = mapping; }

    private Set<String> routes() {
        Set<String> r = new TreeSet<>();
        mapping.getHandlerMethods().keySet().forEach(i -> {
            if (i.getPathPatternsCondition() != null) i.getPathPatternsCondition().getPatterns().forEach(p -> r.add(p.getPatternString()));
        });
        return r;
    }

    public List<Line> lines() {
        Set<String> have = routes();
        List<Line> out = new ArrayList<>();
        for (Item it : ITEMS) {
            out.add(status(it, have));
        }
        return out;
    }

    public static Line status(Item it, Set<String> have) {
        List<String> missing = new ArrayList<>();
        for (String r : it.routes()) if (!have.contains(r)) missing.add(r);
        String s = switch (it.kind()) {
            case NOTUI -> "notui";
            case TASK -> "task";
            case PLANNED -> "planned";
            default -> !missing.isEmpty() ? "notyet" : switch (it.kind()) {
                case DEMO -> "demo";
                case PARTIAL -> "partial";
                default -> it.checked() ? "built" : "builtunchecked";
            };
        };
        return new Line(it, s, missing);
    }
}
