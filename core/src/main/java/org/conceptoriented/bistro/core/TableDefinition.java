package org.conceptoriented.bistro.core;

import java.util.ArrayList;
import java.util.List;

/**
 * This class knows how to populate a table by instantiate its set elements given elements in other tables.
 */
public interface TableDefinition {
	public void populate();
	public List<BistroError> getErrors();
	public List<Element> getDependencies();
}
