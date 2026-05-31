package es.upm.tfg.topomigrator.orchestration.dependency;

import es.upm.tfg.topomigrator.exceptions.CycleDetectedException;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DependencyResolverQualityTest {

    @Test
    public void resolveExecutionOrderPlacesParentsBeforeDependentTables() {
        DependencyResolver resolver = new DependencyResolver();
        Set<String> tables = new LinkedHashSet<>(List.of("order_items", "orders", "customers"));
        List<ForeignKeyDependency> dependencies = List.of(
                new ForeignKeyDependency("customers", "orders"),
                new ForeignKeyDependency("orders", "order_items")
        );

        List<String> order = resolver.resolveExecutionOrder(tables, dependencies)
                .stream()
                .map(TableNode::getName)
                .collect(Collectors.toList());

        assertEquals(List.of("customers", "orders", "order_items"), order);
    }

    @Test
    public void resolveExecutionOrderIgnoresExternalDependenciesAndKeepsDeterministicRoots() {
        DependencyResolver resolver = new DependencyResolver();
        Set<String> tables = new LinkedHashSet<>(List.of("products", "customers"));
        List<ForeignKeyDependency> dependencies = List.of(new ForeignKeyDependency("external_table", "products"));

        List<String> order = resolver.resolveExecutionOrder(tables, dependencies)
                .stream()
                .map(TableNode::getName)
                .collect(Collectors.toList());

        assertEquals(List.of("customers", "products"), order);
    }

    @Test
    public void resolveExecutionOrderFailsWhenGraphContainsCycle() {
        DependencyResolver resolver = new DependencyResolver();
        Set<String> tables = new LinkedHashSet<>(List.of("a", "b"));
        List<ForeignKeyDependency> dependencies = List.of(
                new ForeignKeyDependency("a", "b"),
                new ForeignKeyDependency("b", "a")
        );

        assertThrows(CycleDetectedException.class, () -> resolver.resolveExecutionOrder(tables, dependencies));
    }

    @Test
    public void resolveBestEffortPlanSeparatesExecutableCyclicAndBlockedTables() {
        DependencyResolver resolver = new DependencyResolver();
        Set<String> tables = new LinkedHashSet<>(List.of("projects", "tasks", "task_links", "task_assignments"));
        List<ForeignKeyDependency> dependencies = List.of(
                new ForeignKeyDependency("tasks", "task_links"),
                new ForeignKeyDependency("task_links", "tasks"),
                new ForeignKeyDependency("tasks", "task_assignments")
        );

        DependencyResolver.BestEffortPlan plan = resolver.resolveBestEffortPlan(tables, dependencies);

        assertEquals(List.of("projects"), names(plan.getExecutableOrder()));
        assertEquals(List.of("task_links", "tasks"), names(plan.getCyclicTables()));
        assertEquals(List.of("task_assignments"), names(plan.getBlockedTables()));
    }

    @Test
    public void resolveExecutionOrderIgnoresSelfReferencingForeignKeys() {
        DependencyResolver resolver = new DependencyResolver();
        Set<String> tables = new LinkedHashSet<>(List.of("employees"));
        List<ForeignKeyDependency> dependencies = List.of(new ForeignKeyDependency("employees", "employees"));

        List<String> order = resolver.resolveExecutionOrder(tables, dependencies)
                .stream()
                .map(TableNode::getName)
                .collect(Collectors.toList());

        assertEquals(List.of("employees"), order);
    }

    @Test
    public void resolveExecutionOrderFailsForBlankTableIdentifiers() {
        DependencyResolver resolver = new DependencyResolver();
        Set<String> tables = new LinkedHashSet<>(List.of("customers", " "));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolveExecutionOrder(tables, List.of()));
    }

    private List<String> names(List<TableNode> nodes) {
        return nodes.stream().map(TableNode::getName).collect(Collectors.toList());
    }
}
