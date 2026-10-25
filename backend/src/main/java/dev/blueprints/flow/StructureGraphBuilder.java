package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;
import java.util.*;
import java.util.stream.Collectors;
import static dev.blueprints.flow.FlowModels.*;

/** Граф объявлений. Рёбра означают состав/наследование/упоминание типа, не исполнение. */
public final class StructureGraphBuilder {
    private final List<FlowNode> nodes=new ArrayList<>();
    private final List<FlowEdge> edges=new ArrayList<>();
    private final Map<Node,String> ids=new IdentityHashMap<>();
    private final Map<String,String> external=new LinkedHashMap<>();
    private List<TypeDeclaration<?>> types;
    private final Set<String> edgeKeys=new HashSet<>();
    public MethodFlow build(CompilationUnit unit) {
        nodes.clear(); edges.clear(); ids.clear(); external.clear(); edgeKeys.clear();
        types=new ArrayList<>();
        unit.walk(n->{if(n instanceof TypeDeclaration<?> t) types.add(t);});
        String root=add("file",unit.getStorage().map(s->s.getFileName()).orElse("Java-файл"),unit,
                unit.getPackageDeclaration().map(Object::toString).orElse("Пакет по умолчанию"),"Структура объявлений и зависимостей файла.",null);
        for (TypeDeclaration<?> type:types) {
            String kind=type instanceof ClassOrInterfaceDeclaration c && c.isInterface()?"interface":type instanceof EnumDeclaration?"enum":type instanceof RecordDeclaration?"record":"class";
            String modifiers=type.getModifiers().stream().map(Object::toString).collect(Collectors.joining());
            String title=modifiers+kind+" "+type.getNameAsString();
            if (type instanceof ClassOrInterfaceDeclaration c) {
                if (!c.getTypeParameters().isEmpty()) title+="<"+c.getTypeParameters().stream().map(Object::toString).collect(Collectors.joining(", "))+">";
                if (!c.getExtendedTypes().isEmpty()) title+=" extends "+c.getExtendedTypes().stream().map(Object::toString).collect(Collectors.joining(", "));
                if (!c.getImplementedTypes().isEmpty()) title+=" implements "+c.getImplementedTypes().stream().map(Object::toString).collect(Collectors.joining(", "));
            }
            add(kind,title,type,title,"Объявление пользовательского типа. Поля и методы связаны рёбрами состава.",null);
        }
        for (TypeDeclaration<?> type:types) {
            String parent=type.findAncestor(TypeDeclaration.class).map(ids::get).orElse(root);
            edge(parent,ids.get(type),"содержит");
            if (type instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t->edge(ids.get(type),typeReference(t.getNameWithScope(),type),"extends"));
                c.getImplementedTypes().forEach(t->edge(ids.get(type),typeReference(t.getNameWithScope(),type),"implements"));
            }
            if (type instanceof RecordDeclaration r) {
                r.getImplementedTypes().forEach(t->edge(ids.get(type),typeReference(t.getNameWithScope(),type),"implements"));
                r.getParameters().forEach(p-> {
                    String component=add("field",p.toString(),p,p.toString(),"Компонент record; хранится в поле объекта.",null);
                    edge(ids.get(type),component,"компонент");
                });
            }
            if (type instanceof EnumDeclaration e) {
                e.getImplementedTypes().forEach(t->edge(ids.get(type),typeReference(t.getNameWithScope(),type),"implements"));
                e.getEntries().forEach(v->edge(ids.get(type),add("field",v.getNameAsString(),v,v.toString(),"Константа enum.",null),"константа"));
            }
            for (BodyDeclaration<?> member:type.getMembers()) {
                String id=null;
                if (member instanceof FieldDeclaration f)
                    id=add("field",f.toString(),f,f.toString(),"Поле класса или объекта. Модификаторы, тип, массив и начальное значение сохранены.",null);
                else if (member instanceof CallableDeclaration<?> c) {
                    id=add(member instanceof ConstructorDeclaration?"constructor":"method",FlowGraphBuilder.header(c),c,FlowGraphBuilder.header(c),
                            "Сигнатура с параметрами, модификаторами и throws. Откройте логику, чтобы увидеть тело.",FlowGraphBuilder.id(c));
                    final String from=id;
                    c.getThrownExceptions().forEach(t->edge(from,typeReference(t.asString(),type),"throws"));
                } else if (member instanceof InitializerDeclaration init)
                    id=add("initializer",FlowGraphBuilder.header(init),init,FlowGraphBuilder.header(init),
                            init.isStatic()?"Выполняется при инициализации класса.":"Выполняется при создании объекта перед телом конструктора.",FlowGraphBuilder.id(init));
                if (id!=null) edge(ids.get(type),id,"содержит");
            }
        }
        for (ImportDeclaration imp:unit.getImports()) {
            String text=imp.getNameAsString()+(imp.isAsterisk()?".*":"");
            String node=add("library",(imp.isStatic()?"static ":"")+text,imp,imp.toString(),"Объявленный импорт. Сам импорт не исполняется.",null);
            edge(root,node,"import");
        }
        // Все ссылочные типы: коллекции, исключения, параметры/поля/локальные переменные.
        for (ClassOrInterfaceType ref:unit.findAll(ClassOrInterfaceType.class)) {
            Node context=ref.findAncestor(TypeDeclaration.class).orElse(null);
            if (context!=null) edge(ids.get(context),typeReference(ref.getNameWithScope(),context),"тип");
        }
        Set<String> utility=Set.of("Object","String","Math","System","Arrays","Collections","Comparator","Optional","List","Set","Map","StandardCharsets");
        for (NameExpr name:unit.findAll(NameExpr.class))
            if (utility.contains(name.getNameAsString())) {
                Node context=name.findAncestor(TypeDeclaration.class).orElse(null);
                if (context!=null) edge(ids.get(context),typeReference(name.getNameAsString(),context),"упоминает");
            }
        List<String> notes=List.of("Граф структуры: «содержит», extends/implements, throws и упоминания типов. Это не порядок выполнения. Полный текст выбранного объявления показан справа.");
        return new MethodFlow("structure","","Структура файла","structure",FlowGraphBuilder.range(unit),"structure",notes,List.copyOf(nodes),List.copyOf(edges));
    }
    private String typeReference(String name,Node context) {
        String clean=name.replaceAll("<.*>","");
        String scope=context instanceof TypeDeclaration<?> t ? qualified(t) : FlowGraphBuilder.owner(context);
        // Ищем сначала в ближайшей области видимости, затем во внешних.
        for (String candidateScope=scope;;) {
            String candidate=candidateScope.isEmpty()?clean:candidateScope+"."+clean;
            for (var type:types) if (qualified(type).equals(candidate) || qualified(type).equals(clean)) return ids.get(type);
            int dot=candidateScope.lastIndexOf('.');
            if (candidateScope.isEmpty()) break;
            candidateScope=dot<0?"":candidateScope.substring(0,dot);
        }
        return external.computeIfAbsent(clean,key->add("library",key,context,key,
                "Тип упомянут в исходнике. Внешние типы показаны по написанию; окончательное разрешение имён выполняет javac.",null));
    }
    private String qualified(TypeDeclaration<?> t) { String owner=FlowGraphBuilder.owner(t); return owner.isEmpty()?t.getNameAsString():owner+"."+t.getNameAsString(); }
    private String add(String kind,String label,Node source,String code,String detail,String method) {
        String id="s"+nodes.size(); String compact=label.replaceAll("\\s+"," ").trim();
        nodes.add(new FlowNode(id,kind,compact.length()>140?compact.substring(0,137)+"…":compact,FlowGraphBuilder.range(source),null,null,code,detail,"",method));
        ids.putIfAbsent(source,id); return id;
    }
    private void edge(String from,String to,String label) {
        if (from==null || to==null || from.equals(to) || !edgeKeys.add(from+"|"+to+"|"+label)) return;
        edges.add(new FlowEdge("se"+edges.size(),from,to,label,"structure"));
    }
}
