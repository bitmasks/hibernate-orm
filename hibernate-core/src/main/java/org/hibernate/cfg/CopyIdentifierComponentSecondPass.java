/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cfg;

import java.util.Locale;
import java.util.Map;

import org.hibernate.AnnotationException;
import org.hibernate.AssertionFailure;
import org.hibernate.MappingException;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.internal.util.MutableInteger;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.Selectable;
import org.hibernate.mapping.SimpleValue;

import org.jboss.logging.Logger;

import static org.hibernate.cfg.BinderHelper.isEmptyAnnotationValue;
import static org.hibernate.internal.util.collections.CollectionHelper.mapOfSize;

/**
 * @author Emmanuel Bernard
 */
public class CopyIdentifierComponentSecondPass extends FkSecondPass {
	private static final Logger log = Logger.getLogger( CopyIdentifierComponentSecondPass.class );

	private final String referencedEntityName;
	private final Component component;
	private final MetadataBuildingContext buildingContext;
	private final AnnotatedJoinColumn[] joinColumns;

	public CopyIdentifierComponentSecondPass(
			Component comp,
			String referencedEntityName,
			AnnotatedJoinColumn[] joinColumns,
			MetadataBuildingContext buildingContext) {
		super( comp, joinColumns );
		this.component = comp;
		this.referencedEntityName = referencedEntityName;
		this.buildingContext = buildingContext;
		this.joinColumns = joinColumns;
	}

	@Override
	public String getReferencedEntityName() {
		return referencedEntityName;
	}

	@Override
	public boolean isInPrimaryKey() {
		// This second pass is apparently only ever used to initialize composite identifiers
		return true;
	}

	@Override
	public void doSecondPass(Map<String, PersistentClass> persistentClasses) throws MappingException {
		PersistentClass referencedPersistentClass = persistentClasses.get( referencedEntityName );
		// TODO better error names
		if ( referencedPersistentClass == null ) {
			throw new AnnotationException( "Unknown entity name: " + referencedEntityName );
		}
		if ( ! ( referencedPersistentClass.getIdentifier() instanceof Component ) ) {
			throw new AssertionFailure(
					"Unexpected identifier type on the referenced entity when mapping a @MapsId: "
							+ referencedEntityName
			);
		}
		Component referencedComponent = (Component) referencedPersistentClass.getIdentifier();

		//prepare column name structure
		boolean isExplicitReference = true;
		Map<String, AnnotatedJoinColumn> columnByReferencedName = mapOfSize( joinColumns.length);
		for (AnnotatedJoinColumn joinColumn : joinColumns) {
			final String referencedColumnName = joinColumn.getReferencedColumn();
			if ( referencedColumnName == null || isEmptyAnnotationValue( referencedColumnName ) ) {
				break;
			}
			//JPA 2 requires referencedColumnNames to be case-insensitive
			columnByReferencedName.put( referencedColumnName.toLowerCase(Locale.ROOT), joinColumn );
		}
		//try default column orientation
		if ( columnByReferencedName.isEmpty() ) {
			isExplicitReference = false;
			for ( int i = 0; i < joinColumns.length; i++ ) {
				columnByReferencedName.put( String.valueOf( i ), joinColumns[i] );
			}
		}

		MutableInteger index = new MutableInteger();
		for ( Property referencedProperty : referencedComponent.getProperties() ) {
			if ( referencedProperty.isComposite() ) {
				Property property = createComponentProperty( referencedPersistentClass, isExplicitReference, columnByReferencedName, index, referencedProperty );
				component.addProperty( property );
			}
			else {
				Property property = createSimpleProperty( referencedPersistentClass, isExplicitReference, columnByReferencedName, index, referencedProperty );
				component.addProperty( property );
			}
		}
	}

	private Property createComponentProperty(
			PersistentClass referencedPersistentClass,
			boolean isExplicitReference,
			Map<String, AnnotatedJoinColumn> columnByReferencedName,
			MutableInteger index,
			Property referencedProperty ) {
		Property property = new Property();
		property.setName( referencedProperty.getName() );
		//FIXME set optional?
		//property.setOptional( property.isOptional() );
		property.setPersistentClass( component.getOwner() );
		property.setPropertyAccessorName( referencedProperty.getPropertyAccessorName() );
		Component value = new Component( buildingContext, component.getOwner() );

		property.setValue( value );
		final Component referencedValue = (Component) referencedProperty.getValue();
		value.setTypeName( referencedValue.getTypeName() );
		value.setTypeParameters( referencedValue.getTypeParameters() );
		value.setComponentClassName( referencedValue.getComponentClassName() );


		for ( Property referencedComponentProperty : referencedValue.getProperties() ) {
			if ( referencedComponentProperty.isComposite() ) {
				Property componentProperty = createComponentProperty( referencedValue.getOwner(), isExplicitReference, columnByReferencedName, index, referencedComponentProperty );
				value.addProperty( componentProperty );
			}
			else {
				Property componentProperty = createSimpleProperty( referencedValue.getOwner(), isExplicitReference, columnByReferencedName, index, referencedComponentProperty );
				value.addProperty( componentProperty );
			}
		}

		return property;
	}


	private Property createSimpleProperty(
			PersistentClass referencedPersistentClass,
			boolean isExplicitReference,
			Map<String, AnnotatedJoinColumn> columnByReferencedName,
			MutableInteger index,
			Property referencedProperty ) {
		Property property = new Property();
		property.setName( referencedProperty.getName() );
		//FIXME set optional?
		//property.setOptional( property.isOptional() );
		property.setPersistentClass( component.getOwner() );
		property.setPropertyAccessorName( referencedProperty.getPropertyAccessorName() );
		SimpleValue value = new BasicValue( buildingContext, component.getTable() );
		property.setValue( value );
		final SimpleValue referencedValue = (SimpleValue) referencedProperty.getValue();
		value.copyTypeFrom( referencedValue );

		if ( joinColumns[0].isNameDeferred() ) {
			joinColumns[0].copyReferencedStructureAndCreateDefaultJoinColumns(
					referencedPersistentClass,
					referencedValue,
					value
			);
		}
		else {
			//FIXME take care of Formula
			for ( Selectable selectable : referencedValue.getSelectables() ) {
				if ( !(selectable instanceof Column) ) {
					log.debug( "Encountered formula definition; skipping" );
					continue;
				}
				final Column column = (Column) selectable;
				final AnnotatedJoinColumn joinColumn;
				String logicalColumnName = null;
				if ( isExplicitReference ) {
					logicalColumnName = column.getName();
					//JPA 2 requires referencedColumnNames to be case insensitive
					joinColumn = columnByReferencedName.get( logicalColumnName.toLowerCase(Locale.ROOT ) );
				}
				else {
					joinColumn = columnByReferencedName.get( String.valueOf( index.get() ) );
					index.getAndIncrement();
				}
				if ( joinColumn == null && ! joinColumns[0].isNameDeferred() ) {
					throw new AnnotationException(
							isExplicitReference ?
									"Unable to find column reference in the @MapsId mapping: " + logicalColumnName :
									"Implicit column reference in the @MapsId mapping fails, try to use explicit referenceColumnNames: " + referencedEntityName
					);
				}
				final String columnName = joinColumn == null || joinColumn.isNameDeferred() ? "tata_" + column.getName() : joinColumn
						.getName();

				final Database database = buildingContext.getMetadataCollector().getDatabase();
				final PhysicalNamingStrategy physicalNamingStrategy = buildingContext.getBuildingOptions().getPhysicalNamingStrategy();
				final Identifier explicitName = database.toIdentifier( columnName );
				final Identifier physicalName =  physicalNamingStrategy.toPhysicalColumnName( explicitName, database.getJdbcEnvironment() );
				value.addColumn( new Column( physicalName.render( database.getDialect() ) ) );
				if ( joinColumn != null ) {
					applyComponentColumnSizeValueToJoinColumn( column, joinColumn );
					joinColumn.linkWithValue( value );
				}
				column.setValue( value );
			}
		}
		return property;
	}

	private void applyComponentColumnSizeValueToJoinColumn(Column column, AnnotatedJoinColumn joinColumn) {
		Column mappingColumn = joinColumn.getMappingColumn();
		mappingColumn.setLength( column.getLength() );
		mappingColumn.setPrecision( column.getPrecision() );
		mappingColumn.setScale( column.getScale() );
	}
}
