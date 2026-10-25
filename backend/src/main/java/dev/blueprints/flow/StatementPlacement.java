package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.*;

/** Вставка/извлечение сохраняют обязательные тела, область видимости и форму switch ->. */
final class StatementPlacement {
    private StatementPlacement() {}
    static void insertAt(Node anchor, String position, Statement added) {
        if (position.equals("before") || position.equals("after")) {
            if (!(anchor instanceof Statement statement)) throw EditableCode.invalid("Здесь нельзя вставить соседний оператор.");
            Node parent = statement.getParentNode().orElse(null);
            if (parent instanceof SwitchEntry entry && entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                entry.getStatements().add(indexOf(entry.getStatements(), statement) + (position.equals("after") ? 1 : 0), added);
                return;
            }
            BlockStmt block;
            if (parent instanceof BlockStmt existing) block = existing;
            else {
                if (parent instanceof SwitchEntry entry && entry.getType() == SwitchEntry.Type.EXPRESSION
                        && entry.getParentNode().orElse(null) instanceof SwitchExpr)
                    throw EditableCode.invalid("Выберите заголовок case, чтобы добавить действие перед значением switch.");
                block = new BlockStmt();
                replace(statement, block);
                block.addStatement(statement);
            }
            block.getStatements().add(indexOf(block.getStatements(), statement) + (position.equals("after") ? 1 : 0), added);
            return;
        }
        if (anchor instanceof SwitchEntry entry && entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            entry.getStatements().add(position.endsWith("-end") ? entry.getStatements().size() : 0, added);
            return;
        }
        BlockStmt container;
        if (anchor instanceof SwitchEntry entry) container = switchBody(entry);
        else if (anchor instanceof IfStmt branch) {
            boolean thenBranch = position.startsWith("then-");
            Statement selected = thenBranch ? branch.getThenStmt() : branch.getElseStmt().orElse(null);
            if (selected == null) { container = new BlockStmt(); branch.setElseStmt(container); }
            else container = bodyBlock(selected);
        } else if (anchor instanceof BlockStmt block) container = block;
        else if (anchor instanceof TryStmt t) container = t.getTryBlock();
        else if (anchor instanceof SynchronizedStmt s) container = s.getBody();
        else if (anchor instanceof Statement statement && InsertableCode.loopBody(statement) != null)
            container = bodyBlock(InsertableCode.loopBody(statement));
        else throw EditableCode.invalid("У выбранного блока нет тела для вставки.");
        int index = position.endsWith("-end") ? container.getStatements().size() : 0;
        if (index == 0 && container.getParentNode().orElse(null) instanceof ConstructorDeclaration
                && !container.isEmpty() && container.getStatement(0) instanceof ExplicitConstructorInvocationStmt) index = 1;
        container.getStatements().add(index, added);
    }
    private static BlockStmt switchBody(SwitchEntry entry) {
        if (entry.getType() == SwitchEntry.Type.BLOCK && entry.getStatements().size() == 1
                && entry.getStatement(0) instanceof BlockStmt block) return block;
        BlockStmt block = new BlockStmt();
        boolean implicitYield = entry.getType() == SwitchEntry.Type.EXPRESSION
                && entry.getParentNode().orElse(null) instanceof SwitchExpr;
        var old = new java.util.ArrayList<>(entry.getStatements());
        entry.setStatements(new NodeList<>(block)); entry.setType(SwitchEntry.Type.BLOCK);
        for (Statement statement : old) {
            if (implicitYield && statement instanceof ExpressionStmt expression) {
                YieldStmt yield = new YieldStmt(expression.getExpression());
                expression.getComment().ifPresent(c -> yield.setComment(c.clone()));
                expression.getOrphanComments().forEach(c -> yield.addOrphanComment(c.clone()));
                block.addStatement(yield);
            } else block.addStatement(statement);
        }
        return block;
    }
    private static BlockStmt bodyBlock(Statement body) {
        if (body instanceof BlockStmt block) return block;
        BlockStmt block = new BlockStmt(); replace(body, block); block.addStatement(body); return block;
    }
    static void replace(Statement old, Statement replacement) {
        Node parent = old.getParentNode().orElse(null);
        if (!old.replace(replacement)) throw EditableCode.invalid("Не удалось заменить оператор Java.");
        if (parent instanceof SwitchEntry entry && entry.getType() != SwitchEntry.Type.STATEMENT_GROUP)
            entry.setType(replacement instanceof BlockStmt ? SwitchEntry.Type.BLOCK
                    : replacement instanceof ThrowStmt ? SwitchEntry.Type.THROWS_STATEMENT : SwitchEntry.Type.EXPRESSION);
    }
    static Node remove(Statement statement) {
        Node parent = statement.getParentNode().orElseThrow();
        if (parent instanceof BlockStmt block) block.getStatements().remove(indexOf(block.getStatements(), statement));
        else if (parent instanceof SwitchEntry entry && entry.getType() == SwitchEntry.Type.STATEMENT_GROUP)
            entry.getStatements().remove(indexOf(entry.getStatements(), statement));
        else replace(statement, new BlockStmt()); // if/loop/label/arrow всегда сохраняют обязательное тело.
        return parent;
    }
    private static int indexOf(NodeList<Statement> statements, Statement target) {
        for (int i=0;i<statements.size();i++) if (statements.get(i) == target) return i;
        throw EditableCode.invalid("Не удалось определить место оператора.");
    }
}
