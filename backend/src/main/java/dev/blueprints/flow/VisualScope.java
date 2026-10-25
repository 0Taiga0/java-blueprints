package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static dev.blueprints.flow.VisualModels.*;
import dev.blueprints.flow.VisualModels.Parameter;

/** Lexical variables and an explicit call catalogue for a particular AST location. */
final class VisualScope {
    record Binding(Variable option, VariableDeclarator declaration) {}
    record Callable(Method option, String scope, String name) {}
    final LinkedHashMap<String, Binding> variables = new LinkedHashMap<>();
    final LinkedHashMap<String, Callable> calls = new LinkedHashMap<>();
    final LinkedHashSet<String> types = new LinkedHashSet<>(List.of("int","long","double","float","boolean","char","byte","short",
            "String","Integer","Long","Double","Float","Boolean","Character","Byte","Short","Object","int[]","String[]","double[]","boolean[]"));
    final FlowMutationSession session;
    final TypeDeclaration<?> owner;
    final boolean staticContext;
    final Node point;

    VisualScope(FlowMutationSession session, Node point) {
        this.session=session; this.point=point;
        owner=session.declaration.findAncestor(TypeDeclaration.class).orElseThrow();
        staticContext=session.declaration instanceof MethodDeclaration method && method.isStatic()
                || session.declaration instanceof InitializerDeclaration initializer && initializer.isStatic();
        session.unit.findAll(TypeDeclaration.class).forEach(type -> types.add(type.getNameAsString()));
        session.unit.findAll(com.github.javaparser.ast.type.Type.class).forEach(type -> {
            if (!type.isVoidType() && !type.isUnknownType() && !type.isVarType() && !type.isWildcardType()
                    && !type.isIntersectionType() && !type.isUnionType()) types.add(type.toString());
        });
        for (FieldDeclaration field : owner.getFields()) if (!staticContext || field.isStatic())
            for (VariableDeclarator variable : field.getVariables()) put(variable, !field.isFinal());
        if (session.declaration instanceof CallableDeclaration<?> declaration)
            declaration.getParameters().forEach(parameter -> variables.put(parameter.getNameAsString(),
                    new Binding(new Variable(parameter.getNameAsString(), parameter.getType().toString()+(parameter.isVarArgs()?"[]":""),!parameter.isFinal()),null)));
        // Outer-to-inner ancestry makes a nearer shadowed binding win.
        List<Node> ancestors=new ArrayList<>();
        for(Node n=point; n!=null && n!=session.declaration; n=n.getParentNode().orElse(null)) ancestors.add(n);
        Collections.reverse(ancestors);
        for(int i=0;i<ancestors.size();i++) {
            Node container=ancestors.get(i), child=i+1<ancestors.size()?ancestors.get(i+1):null;
            if(container instanceof BlockStmt block && child!=null) preceding(block.getStatements(),child);
            else if(container instanceof SwitchEntry entry && child!=null) preceding(entry.getStatements(),child);
            else if(container instanceof ForStmt loop && child!=null && !loop.getInitialization().contains(child))
                loop.getInitialization().forEach(this::declaration);
            else if(container instanceof ForEachStmt loop && child==loop.getBody()) declaration(loop.getVariable());
            else if(container instanceof CatchClause clause) {
                var parameter=clause.getParameter();
                variables.put(parameter.getNameAsString(),new Binding(new Variable(parameter.getNameAsString(),parameter.getType().toString(),!parameter.isFinal()),null));
            } else if(container instanceof TryStmt attempt && child==attempt.getTryBlock()) attempt.getResources().forEach(this::declaration);
        }
        catalogue();
    }
    private void preceding(NodeList<Statement> statements, Node child) {
        for(Statement statement:statements) {
            if(statement==child) break;
            if(statement instanceof ExpressionStmt expression) declaration(expression.getExpression());
        }
    }
    private void declaration(Expression expression) {
        if(expression instanceof VariableDeclarationExpr declaration)
            declaration.getVariables().forEach(variable -> put(variable,!declaration.isFinal()));
    }
    void put(VariableDeclarator variable, boolean writable) {
        variables.put(variable.getNameAsString(),new Binding(new Variable(variable.getNameAsString(),variable.getType().toString(),writable),variable));
    }
    void put(String name,String type,boolean writable) { variables.put(name,new Binding(new Variable(name,type,writable),null)); }
    Variable variable(String name) {
        Binding binding=variables.get(name);
        if(binding==null) throw EditableCode.invalid("Переменная «"+name+"» недоступна в этом месте. Выберите её из списка.");
        return binding.option();
    }
    private void catalogue() {
        for(MethodDeclaration method:owner.getMethods()) if(!staticContext || method.isStatic())
            own(method,null);
        add("System.out","println","void","String"); add("System.out","println","void","int");
        add("System.out","println","void","double"); add("System.out","println","void","boolean");
        add("System.out","println","void","Object"); add("System.out","println","void");
        add("System.out","print","void","String"); add("System.out","print","void","int");
        add("System.err","println","void","String");
        for(String type:List.of("int","long","float","double")) {
            add("Math","abs",type,type); add("Math","min",type,type,type); add("Math","max",type,type,type);
        }
        for(String name:List.of("sqrt","floor","ceil")) add("Math",name,"double","double");
        add("Math","pow","double","double","double"); add("Math","round","long","double");
        add("Integer","parseInt","int","String"); add("Double","parseDouble","double","String");
        add("Boolean","parseBoolean","boolean","String");
        add("String","valueOf","String","int"); add("String","valueOf","String","Object");
        for(Binding binding:new ArrayList<>(variables.values())) {
            Variable variable=binding.option(); String name=variable.name(),type=simple(variable.type());
            if(type.equals("String")) {
                add(name,"length","int"); add(name,"isEmpty","boolean"); add(name,"isBlank","boolean");
                for(String method:List.of("trim","strip","toLowerCase","toUpperCase")) add(name,method,"String");
                add(name,"equals","boolean","Object"); add(name,"equalsIgnoreCase","boolean","String");
                add(name,"contains","boolean","String"); add(name,"startsWith","boolean","String");
                add(name,"endsWith","boolean","String"); add(name,"substring","String","int");
                add(name,"substring","String","int","int"); add(name,"charAt","char","int");
            }
            if(Set.of("List","ArrayList","LinkedList","Set","HashSet","TreeSet","Collection","Map","HashMap","TreeMap").contains(type)) {
                add(name,"size","int"); add(name,"isEmpty","boolean"); add(name,"clear","void");
                String argument=elementType(variable.type());
                if(!Set.of("Map","HashMap","TreeMap").contains(type)) {
                    add(name,"add","boolean",argument); add(name,"contains","boolean","Object");
                    if(Set.of("List","ArrayList","LinkedList").contains(type)) add(name,"get",argument,"int");
                }
            }
            for(TypeDeclaration<?> declared:session.unit.findAll(TypeDeclaration.class)) if(declared.getNameAsString().equals(type))
                for(MethodDeclaration method:declared.getMethods()) if(!method.isStatic() && (declared==owner || !method.isPrivate())) own(method,name);
        }
    }
    private void own(MethodDeclaration method,String receiver) {
        if(method.getParameters().stream().anyMatch(com.github.javaparser.ast.body.Parameter::isVarArgs)) return;
        List<Parameter> parameters=method.getParameters().stream().map(p->new Parameter(p.getNameAsString(),p.getType().toString())).toList();
        add(receiver,method.getNameAsString(),method.getType().toString(),parameters);
    }
    private void add(String scope,String name,String result,String... parameters) {
        List<Parameter> options=new ArrayList<>();
        for(int i=0;i<parameters.length;i++) options.add(new Parameter("аргумент "+(i+1),parameters[i]));
        add(scope,name,result,options);
    }
    private void add(String scope,String name,String result,List<Parameter> parameters) {
        String display=(scope==null?"":scope+".")+name+"("+String.join(", ",parameters.stream().map(Parameter::type).toList())+")";
        calls.put(display,new Callable(new Method(display,display,result,parameters),scope,name));
    }
    static String simple(String type) {
        String base=type.contains("<")?type.substring(0,type.indexOf('<')):type;
        return base.substring(base.lastIndexOf('.')+1);
    }
    static String elementType(String type) {
        if(type.endsWith("[]")) return type.substring(0,type.length()-2);
        int begin=type.indexOf('<'),end=type.lastIndexOf('>');
        if(begin<0 || end<=begin || type.substring(begin+1,end).contains(",") || type.contains("?")) return "Object";
        return type.substring(begin+1,end).trim();
    }
}
