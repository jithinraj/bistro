package org.conceptoriented.bistro.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stream schema stores the complete data state and is able to consistently update it. 
 */
public class Schema {
	
	private final UUID id;
	public UUID getId() {
		return id;
	}

	private String name;
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	
	//
	// Tables
	//
	
	private List<Table> tables = new ArrayList<Table>();
	public List<Table> getTables() {
		return this.tables;
	}
	public Table getTable(String table) {
        Table ret = this.tables.stream().filter(x -> x.getName().equalsIgnoreCase(table)).findAny().orElse(null);
        return ret;
	}
	public Table getTableById(String id) {
        Table ret = this.tables.stream().filter(x -> x.getId().toString().equals(id)).findAny().orElse(null);
        return ret;
	}

	public Table createTable(String name) {
		Table tab = this.getTable(name);
		if(tab != null) return tab; // Already exists

		tab = new Table(this, name);
		this.tables.add(tab);
		return tab;
	}
    public void deleteTable(String id) {
		Table tab = getTableById(id);

		// Remove input columns
		List<Column> inColumns = this.columns.stream().filter(x -> x.getInput().equals(tab)).collect(Collectors.<Column>toList());
		this.columns.removeAll(inColumns);
		
		// Remove output columns
		List<Column> outColumns = this.columns.stream().filter(x -> x.getOutput().equals(tab)).collect(Collectors.<Column>toList());
		this.columns.removeAll(outColumns);
		
		// Remove table itself
		this.tables.remove(tab);
	}

	//
	// Columns
	//

	private List<Column> columns = new ArrayList<Column>();
	public List<Column> getColumns() {
		return this.columns;
	}
	public List<Column> getColumns(Table table) {
		List<Column> res = this.columns.stream().filter(x -> x.getInput().equals(table)).collect(Collectors.<Column>toList());
		return res;
	}
	public List<Column> getColumns(String table) {
		List<Column> res = this.columns.stream().filter(x -> x.getInput().getName().equalsIgnoreCase(table)).collect(Collectors.<Column>toList());
		return res;
	}
	public Column getColumn(Table table, String column) {
		Column ret = this.columns.stream().filter(x -> x.getInput().equals(table) && x.getName().equalsIgnoreCase(column)).findAny().orElse(null);
		return ret;
	}
	public Column getColumn(String table, String column) {
        Column ret = this.columns.stream().filter(x -> x.getInput().getName().equalsIgnoreCase(table) && x.getName().equalsIgnoreCase(column)).findAny().orElse(null);
        return ret;
	}
	public Column getColumnById(String id) {
        Column ret = this.columns.stream().filter(x -> x.getId().toString().equals(id)).findAny().orElse(null);
        return ret;
	}

	public Column createColumn(String input, String name, String output) {
		Column col = new Column(this, name, input, output);
		this.columns.add(col);
		return col;
	}
	public List<Column> createColumns(String input, List<String> names, List<String> outputs) {
		List<Column> cols = new ArrayList<Column>();
		
		for(int i=0; i<names.size(); i++) {
			Column col = this.getColumn(input, names.get(i));
			if(col != null) continue; // Already exists

			col = this.createColumn(input, names.get(i), outputs.get(i));
			cols.add(col);
		}

		return cols;
	}
	public void deleteColumn(String id) {
		Column col = this.getColumnById(id);
		this.columns.remove(col);
	}

	//
	// Translation (parse and bind formulas, prepare for evaluation)
	//
	
	/**
	 * Parse, bind and build all column formulas in the schema. 
	 * Generate dependencies.
	 */
	public void translate() {
		// Translate individual columns
		for(Column col : this.columns) {
			if(!col.isDerived()) continue;
			col.translate();
		}
	}

	//
	// Evaluate (re-compute dirty, selected or all function outputs)
	//
	
	/**
	 * Evaluate all columns of the schema which can be evaluated and need evaluation (dirty output).
	 * 
	 * The order of column evaluation is determined by the dependency graph.
	 * Can evaluate depends on the error status: translate errors, evaluate errors, self-dependence errors, and these errors in dependencies.
	 * Need evaluate depends on formula changes, data output changes, set changes, and these changes in dependencies.
	 * 
	 * Finally, the status of each evaluated column is cleaned (made up-to-date). 
	 */
	public void evaluate() {
		
		List<Column> done = new ArrayList<Column>();
		for(List<Column> cols = this.getStartingColumns(); cols.size() > 0; cols = this.getNextColumnsEvaluatable(done)) { // Loop on expansion layers of dependencies forward
			for(Column col : cols) {
				if(!col.isDerived()) continue;
				// TODO: Detect also evaluate errors that could have happened before in this same evaluate loop and prevent this column from evaluation
				// Evaluate errors have to be also taken into account when generating next layer of columns
				BistroError de = col.getTranslateError();
				if(de == null || de.code == BistroErrorCode.NONE) {
					col.evaluate();
				}
			}
			done.addAll(cols);
		}

	}
	
	//
	// Dependency graph (needed to determine the order of column evaluations, generated by translation)
	//
	
	protected List<Column> getStartingColumns() { // Return all columns which do not depend on other columns (starting nodes in the dependency graph)
		List<Column> res = this.columns.stream().filter(x -> x.isStartingColumn()).collect(Collectors.<Column>toList());
		return res;
	}
	protected List<Column> getNextDependencies(Column col) { // Return all columns have the specified column in their dependencies (but can depend also on other columns)
		List<Column> res = this.columns.stream().filter(x -> x.getDependencies() != null && x.getDependencies().contains(col)).collect(Collectors.<Column>toList());
		return res;
	}
	protected List<Column> getNextColumns(List<Column> previousColumns) { // Get columns with all their dependencies in the specified list
		List<Column> ret = new ArrayList<Column>();
		
		for(Column col : this.columns) {

			if(previousColumns.contains(col)) continue; // Already in the list. Ccan it really happen without cycles?
			List<Column> deps = col.getDependencies();
			if(deps == null) continue; // Something wrong

			if(previousColumns.containsAll(deps)) { // All column dependencies are in the list
				ret.add(col); 
			}
		}
		
		return ret;
	}
	protected List<Column> getNextColumnsEvaluatable(List<Column> previousColumns) { // Get columns with all their dependencies in the specified list and having no translation errors (own or inherited)
		List<Column> ret = new ArrayList<Column>();
		
		for(Column col : this.columns) {
			if(previousColumns.contains(col)) continue;  // Already in the list. Ccan it really happen without cycles?
			List<Column> deps = col.getDependencies();
			if(deps == null) continue; // Something wrong

			// If it has errors then exclude it (cannot be evaluated)
			if(col.getTranslateError() != null && col.getTranslateError().code != BistroErrorCode.NONE) {
				continue;
			}

			// If one of its dependencies has errors then exclude it (cannot be evaluated)
			Column errCol = deps.stream().filter(x -> x.getTranslateError() != null && x.getTranslateError().code != BistroErrorCode.NONE).findAny().orElse(null);
			if(errCol != null) continue;
			
			if(previousColumns.containsAll(deps)) { // All deps have to be evaluated (non-dirty)
				ret.add(col); 
			}
		}
		
		return ret;
	}

	//
	// Serialization and construction
	//

	@Override
	public String toString() {
		return "[" + name + "]";
	}
	
	public Schema(String name) {
		this.id = UUID.randomUUID();
		this.name = name;
		
		// Create primitive tables
		Table doubleType = createTable("Double");
		Table stringType = createTable("String");
	}

}
