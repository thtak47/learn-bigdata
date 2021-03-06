package com.hqbhoho.bigdata.hive.parse1.hooks;

import com.google.common.collect.Lists;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.gson.stream.JsonWriter;
import com.hqbhoho.bigdata.hive.parse1.utils.JDBCClient;
import org.apache.commons.collections.SetUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.ObjectPair;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.QueryPlan;
import org.apache.hadoop.hive.ql.exec.ColumnInfo;
import org.apache.hadoop.hive.ql.exec.SelectOperator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.hooks.Entity;
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext;
import org.apache.hadoop.hive.ql.hooks.HookContext;
import org.apache.hadoop.hive.ql.hooks.HookContext.HookType;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.BaseColumnInfo;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.Dependency;
import org.apache.hadoop.hive.ql.hooks.WriteEntity;
import org.apache.hadoop.hive.ql.optimizer.lineage.LineageCtx.Index;
import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * describe:
 *
 * @author hqbhoho
 * @version [v1.0]
 * @date 2020/05/19
 */
public class LineageInfoToDBHook implements ExecuteWithHookContext {

    private static final Log LOG = LogFactory.getLog(LineageInfoToDBHook.class);

    private static final HashSet<String> OPERATION_NAMES = new HashSet<String>();

    static {
        OPERATION_NAMES.add(HiveOperation.QUERY.getOperationName());
        OPERATION_NAMES.add(HiveOperation.CREATETABLE_AS_SELECT.getOperationName());
        OPERATION_NAMES.add(HiveOperation.ALTERVIEW_AS.getOperationName());
        OPERATION_NAMES.add(HiveOperation.CREATEVIEW.getOperationName());
    }

    private static final String FORMAT_VERSION = "1.0";

    final static class Edge {
        public static enum Type {
            PROJECTION, PREDICATE
        }

        private Set<Vertex> sources;
        private Set<Vertex> targets;
        private String expr;
        private Type type;

        Edge(Set<Vertex> sources, Set<Vertex> targets, String expr, Type type) {
            this.sources = sources;
            this.targets = targets;
            this.expr = expr;
            this.type = type;
        }
    }

    final static class Vertex {
        public static enum Type {
            COLUMN, TABLE
        }

        private Type type;
        private String label;
        private int id;

        Vertex(String label) {
            this(label, Type.COLUMN);
        }

        Vertex(String label, Type type) {
            this.label = label;
            this.type = type;
        }

        @Override
        public int hashCode() {
            return label.hashCode() + type.hashCode() * 3;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Vertex)) {
                return false;
            }
            Vertex vertex = (Vertex) obj;
            return label.equals(vertex.label) && type == vertex.type;
        }
    }

    @Override
    public void run(HookContext hookContext) {
        // 获取数据库连接
        JDBCClient mysqlClient = JDBCClient.getMysqlClient();

        assert (hookContext.getHookType() == HookType.POST_EXEC_HOOK);
        QueryPlan plan = hookContext.getQueryPlan();
        Index index = hookContext.getIndex();
        SessionState ss = SessionState.get();
        if (ss != null && index != null
                && OPERATION_NAMES.contains(plan.getOperationName())
                && !plan.isExplain()) {
            // 获取相关字段 数据库需要插入的字段
            try {
                // 解析的sql语句
                String queryText = plan.getQueryStr().trim();
                // 获取HiveConf 中添加的有关脚本的路径信息
                String script_path = hookContext.getConf().get("script_path");
                // 获取当前日期
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
                String now = formatter.format(LocalDateTime.now());

                long queryTime = plan.getQueryStartTime().longValue();
                if (queryTime == 0) queryTime = System.currentTimeMillis();
                // 血缘开始解析时间戳
                long start_time = queryTime ;
                // 血缘解析时长
                long duration = System.currentTimeMillis() - queryTime;
                // 血缘解析用户
                String user = hookContext.getUgi().getUserName();
                // 获取血缘边以及血缘顶点  来组织血缘图
                // 只保留PROJECT的边
                List<Edge> edges = getEdges(plan, index);
                // 遍历edges，输出血缘关系
                edges.stream().forEach(edge -> {
                    Set<Vertex> sources = edge.sources;
                    Set<Vertex> targets = edge.targets;
                    for (Vertex target : targets) {
                        for (Vertex source : sources) {
                            String sql = "insert into test.t_lineage_relation " +
                                    "(res_col_id_from," +
                                    "res_col_id_to," +
                                    "parse_time," +
                                    "exec_sql_analyzer_duration," +
                                    "exec_sql_str_example," +
                                    "exec_sql_analyzer_user," +
                                    "date_id," +
                                    "script_path ) values (?,?,?,?,?,?,?,?)";

                            try {
                                Connection conn = mysqlClient.getConnection();
                                PreparedStatement preparedStatement = conn.prepareStatement(sql);
                                preparedStatement.setString(1,source.label);
                                preparedStatement.setString(2,target.label);
                                preparedStatement.setTimestamp(3,new Timestamp(start_time));
                                preparedStatement.setLong(4,duration);
                                preparedStatement.setString(5,queryText);
                                preparedStatement.setString(6,user);
                                preparedStatement.setInt(7,Integer.valueOf(now));
                                preparedStatement.setString(8,script_path);
                                preparedStatement.executeUpdate();
                                preparedStatement.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }


                        }
                    }
                });

            } catch (Throwable t) {
                // Don't fail the query just because of any lineage issue.
                log("Failed to log lineage graph, query is not affected\n"
                        + org.apache.hadoop.util.StringUtils.stringifyException(t));
            }
        }
    }

    /**
     * Log an error to console if available.
     */
    private void log(String error) {
        LogHelper console = SessionState.getConsole();
        if (console != null) {
            console.printError(error);
        }
    }

    /**
     * Based on the final select operator, find out all the target columns.
     * For each target column, find out its sources based on the dependency index.
     */
    private List<Edge> getEdges(QueryPlan plan, Index index) {
        LinkedHashMap<String, ObjectPair<SelectOperator,
                org.apache.hadoop.hive.ql.metadata.Table>> finalSelOps = index.getFinalSelectOps();
        Map<String, Vertex> vertexCache = new LinkedHashMap<String, Vertex>();
        List<Edge> edges = new ArrayList<Edge>();
        for (ObjectPair<SelectOperator,
                org.apache.hadoop.hive.ql.metadata.Table> pair : finalSelOps.values()) {
            List<FieldSchema> fieldSchemas = plan.getResultSchema().getFieldSchemas();
            SelectOperator finalSelOp = pair.getFirst();
            org.apache.hadoop.hive.ql.metadata.Table t = pair.getSecond();
            String destTableName = null;
            List<String> colNames = null;
            if (t != null) {
                destTableName = t.getDbName() + "." + t.getTableName();
                fieldSchemas = t.getCols();
            } else {
                // Based on the plan outputs, find out the target table name and column names.
                for (WriteEntity output : plan.getOutputs()) {
                    Entity.Type entityType = output.getType();
                    if (entityType == Entity.Type.TABLE
                            || entityType == Entity.Type.PARTITION) {
                        t = output.getTable();
                        destTableName = t.getDbName() + "." + t.getTableName();
                        List<FieldSchema> cols = t.getCols();
                        if (cols != null && !cols.isEmpty()) {
                            colNames = Utilities.getColumnNamesFromFieldSchema(cols);
                        }
                        break;
                    }
                }
            }
            Map<ColumnInfo, Dependency> colMap = index.getDependencies(finalSelOp);
            List<Dependency> dependencies = colMap != null ? Lists.newArrayList(colMap.values()) : null;
            int fields = fieldSchemas.size();
            if (t != null && colMap != null && fields < colMap.size()) {
                // Dynamic partition keys should be added to field schemas.
                List<FieldSchema> partitionKeys = t.getPartitionKeys();
                int dynamicKeyCount = colMap.size() - fields;
                int keyOffset = partitionKeys.size() - dynamicKeyCount;
                if (keyOffset >= 0) {
                    fields += dynamicKeyCount;
                    for (int i = 0; i < dynamicKeyCount; i++) {
                        FieldSchema field = partitionKeys.get(keyOffset + i);
                        fieldSchemas.add(field);
                        if (colNames != null) {
                            colNames.add(field.getName());
                        }
                    }
                }
            }
            if (dependencies == null || dependencies.size() != fields) {
                log("Result schema has " + fields
                        + " fields, but we don't get as many dependencies");
            } else {
                // Go through each target column, generate the lineage edges.
                Set<Vertex> targets = new LinkedHashSet<Vertex>();
                for (int i = 0; i < fields; i++) {
                    Vertex target = getOrCreateVertex(vertexCache,
                            getTargetFieldName(i, destTableName, colNames, fieldSchemas),
                            Vertex.Type.COLUMN);
                    targets.add(target);
                    Dependency dep = dependencies.get(i);
                    addEdge(vertexCache, edges, dep.getBaseCols(), target,
                            dep.getExpr(), Edge.Type.PROJECTION);
                }
                // move Predicate edge
                /*Set<Predicate> conds = index.getPredicates(finalSelOp);
                if (conds != null && !conds.isEmpty()) {
                    for (Predicate cond: conds) {
                        addEdge(vertexCache, edges, cond.getBaseCols(),
                                new LinkedHashSet<Vertex>(targets), cond.getExpr(),
                                Edge.Type.PREDICATE);
                    }
                }*/
            }
        }
        return edges;
    }

    private void addEdge(Map<String, Vertex> vertexCache, List<Edge> edges,
                         Set<BaseColumnInfo> srcCols, Vertex target, String expr, Edge.Type type) {
        Set<Vertex> targets = new LinkedHashSet<Vertex>();
        targets.add(target);
        addEdge(vertexCache, edges, srcCols, targets, expr, type);
    }

    /**
     * Find an edge from all edges that has the same source vertices.
     * If found, add the more targets to this edge's target vertex list.
     * Otherwise, create a new edge and add to edge list.
     */
    private void addEdge(Map<String, Vertex> vertexCache, List<Edge> edges,
                         Set<BaseColumnInfo> srcCols, Set<Vertex> targets, String expr, Edge.Type type) {
        Set<Vertex> sources = createSourceVertices(vertexCache, srcCols);
        Edge edge = findSimilarEdgeBySources(edges, sources, expr, type);
        if (edge == null) {
            edges.add(new Edge(sources, targets, expr, type));
        } else {
            edge.targets.addAll(targets);
        }
    }

    /**
     * Convert a list of columns to a set of vertices.
     * Use cached vertices if possible.
     */
    private Set<Vertex> createSourceVertices(
            Map<String, Vertex> vertexCache, Collection<BaseColumnInfo> baseCols) {
        Set<Vertex> sources = new LinkedHashSet<Vertex>();
        if (baseCols != null && !baseCols.isEmpty()) {
            for (BaseColumnInfo col : baseCols) {
                Table table = col.getTabAlias().getTable();
                if (table.isTemporary()) {
                    // Ignore temporary tables
                    continue;
                }
                Vertex.Type type = Vertex.Type.TABLE;
                String tableName = table.getDbName() + "." + table.getTableName();
                FieldSchema fieldSchema = col.getColumn();
                String label = tableName;
                if (fieldSchema != null) {
                    type = Vertex.Type.COLUMN;
                    label = tableName + "." + fieldSchema.getName();
                }
                sources.add(getOrCreateVertex(vertexCache, label, type));
            }
        }
        return sources;
    }

    /**
     * Find a vertex from a cache, or create one if not.
     */
    private Vertex getOrCreateVertex(
            Map<String, Vertex> vertices, String label, Vertex.Type type) {
        Vertex vertex = vertices.get(label);
        if (vertex == null) {
            vertex = new Vertex(label, type);
            vertices.put(label, vertex);
        }
        return vertex;
    }

    /**
     * Find an edge that has the same type, expression, and sources.
     */
    private Edge findSimilarEdgeBySources(
            List<Edge> edges, Set<Vertex> sources, String expr, Edge.Type type) {
        for (Edge edge : edges) {
            if (edge.type == type && StringUtils.equals(edge.expr, expr)
                    && SetUtils.isEqualSet(edge.sources, sources)) {
                return edge;
            }
        }
        return null;
    }

    /**
     * Generate normalized name for a given target column.
     */
    private String getTargetFieldName(int fieldIndex,
                                      String destTableName, List<String> colNames, List<FieldSchema> fieldSchemas) {
        String fieldName = fieldSchemas.get(fieldIndex).getName();
        String[] parts = fieldName.split("\\.");
        if (destTableName != null) {
            String colName = parts[parts.length - 1];
            if (colNames != null && !colNames.contains(colName)) {
                colName = colNames.get(fieldIndex);
            }
            return destTableName + "." + colName;
        }
        if (parts.length == 2 && parts[0].startsWith("_u")) {
            return parts[1];
        }
        return fieldName;
    }

    /**
     * Get all the vertices of all edges. Targets at first,
     * then sources. Assign id to each vertex.
     */
    private Set<Vertex> getVertices(List<Edge> edges) {
        Set<Vertex> vertices = new LinkedHashSet<Vertex>();
        for (Edge edge : edges) {
            vertices.addAll(edge.targets);
        }
        for (Edge edge : edges) {
            vertices.addAll(edge.sources);
        }

        // Assign ids to all vertices,
        // targets at first, then sources.
        int id = 0;
        for (Vertex vertex : vertices) {
            vertex.id = id++;
        }
        return vertices;
    }

    /**
     * Get all the vertices of all edges. Targets at first,
     * then sources. Assign id to each vertex.
     */
    private List<Vertex> getVerticesList(List<Edge> edges) {
        Set<Vertex> vertices = new LinkedHashSet<Vertex>();
        for (Edge edge : edges) {
            vertices.addAll(edge.targets);
        }
        for (Edge edge : edges) {
            vertices.addAll(edge.sources);
        }

        ArrayList<Vertex> res = new ArrayList();
        // Assign ids to all vertices,
        // targets at first, then sources.
        int id = 0;
        for (Vertex vertex : vertices) {
            vertex.id = id++;
            res.add(vertex);
        }
        return res;
    }

    /**
     * Write out an JSON array of edges.
     */
    private void writeEdges(JsonWriter writer, List<Edge> edges) throws IOException {
        writer.name("edges");
        writer.beginArray();
        for (Edge edge : edges) {
            writer.beginObject();
            writer.name("sources");
            writer.beginArray();
            for (Vertex vertex : edge.sources) {
                writer.value(vertex.id);
            }
            writer.endArray();
            writer.name("targets");
            writer.beginArray();
            for (Vertex vertex : edge.targets) {
                writer.value(vertex.id);
            }
            writer.endArray();
            if (edge.expr != null) {
                writer.name("expression").value(edge.expr);
            }
            writer.name("edgeType").value(edge.type.name());
            writer.endObject();
        }
        writer.endArray();
    }

    /**
     * Write out an JSON array of vertices.
     */
    private void writeVertices(JsonWriter writer, Set<Vertex> vertices) throws IOException {
        writer.name("vertices");
        writer.beginArray();
        for (Vertex vertex : vertices) {
            writer.beginObject();
            writer.name("id").value(vertex.id);
            writer.name("vertexType").value(vertex.type.name());
            writer.name("vertexId").value(vertex.label);
            writer.endObject();
        }
        writer.endArray();
    }

    /**
     * Generate query string md5 hash.
     */
    private String getQueryHash(String queryStr) {
        Hasher hasher = Hashing.md5().newHasher();
        hasher.putString(queryStr);
        return hasher.hash().toString();
    }
}
