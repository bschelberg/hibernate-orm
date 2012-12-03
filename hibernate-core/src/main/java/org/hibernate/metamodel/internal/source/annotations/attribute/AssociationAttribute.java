/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.metamodel.internal.source.annotations.attribute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.CascadeType;
import javax.persistence.FetchType;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;

import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.LazyToOneOption;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.engine.FetchStyle;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.mapping.PropertyGeneration;
import org.hibernate.metamodel.internal.source.annotations.attribute.type.AttributeTypeResolver;
import org.hibernate.metamodel.internal.source.annotations.attribute.type.AttributeTypeResolverImpl;
import org.hibernate.metamodel.internal.source.annotations.attribute.type.CompositeAttributeTypeResolver;
import org.hibernate.metamodel.internal.source.annotations.entity.EntityBindingContext;
import org.hibernate.metamodel.internal.source.annotations.util.EnumConversionHelper;
import org.hibernate.metamodel.internal.source.annotations.util.HibernateDotNames;
import org.hibernate.metamodel.internal.source.annotations.util.JPADotNames;
import org.hibernate.metamodel.internal.source.annotations.util.JandexHelper;
import org.hibernate.metamodel.spi.source.MappingException;

/**
 * Represents an association attribute.
 *
 * @author Hardy Ferentschik
 * @author Brett Meyer
 */
public class AssociationAttribute extends MappedAttribute {
	private static final CoreMessageLogger coreLogger = Logger.getMessageLogger(
			CoreMessageLogger.class,
			AssociationAttribute.class.getName()
	);

	private final boolean ignoreNotFound;
	private final String referencedEntityType;
	private final String mappedBy;
	private final Set<CascadeType> cascadeTypes;
	private final Set<org.hibernate.annotations.CascadeType> hibernateCascadeTypes;
	private final boolean isOptional;
	private final boolean isLazy;
	private final boolean isUnWrapProxy;
	private final boolean isOrphanRemoval;
	private final FetchStyle fetchStyle;
	private final boolean mapsId;
	private final String referencedIdAttributeName;
	private final List<Column> joinColumnValues;
	private final List<Column> inverseJoinColumnValues;
	private final AnnotationInstance joinTableAnnotation;
	private AttributeTypeResolver resolver;

	public static AssociationAttribute createAssociationAttribute(
			String name,
			Class<?> attributeType,
			Nature attributeNature,
			String accessType,
			Map<DotName, List<AnnotationInstance>> annotations,
			EntityBindingContext context) {
		return new AssociationAttribute(
				name,
				attributeType,
				attributeType,
				attributeNature,
				accessType,
				annotations,
				context
		);
	}

	AssociationAttribute(
			String name,
			Class<?> attributeType,
			Class<?> referencedAttributeType,
			Nature attributeNature,
			String accessType,
			Map<DotName, List<AnnotationInstance>> annotations,
			EntityBindingContext context) {
		super( name, attributeType, attributeNature, accessType, annotations, context );
		this.ignoreNotFound = determineNotFoundBehavior();

		AnnotationInstance associationAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				attributeNature.getAnnotationDotName()
		);

		// using jandex we don't really care which exact type of annotation we are dealing with
		this.referencedEntityType = determineReferencedEntityType( associationAnnotation, referencedAttributeType );
		this.mappedBy = determineMappedByAttributeName( associationAnnotation );
		this.isOptional = determineOptionality( associationAnnotation );
		this.isLazy = determineIsLazy( associationAnnotation );
		this.isUnWrapProxy = determinIsUnwrapProxy();
		this.isOrphanRemoval = determineOrphanRemoval( associationAnnotation );
		this.cascadeTypes = determineCascadeTypes( associationAnnotation );
		this.hibernateCascadeTypes = determineHibernateCascadeTypes( annotations );
		this.joinColumnValues = determineJoinColumnAnnotations( annotations );
		this.inverseJoinColumnValues = determineInverseJoinColumnAnnotations( annotations );

		this.fetchStyle = determineFetchStyle();
		this.referencedIdAttributeName = determineMapsId();
		this.mapsId = referencedIdAttributeName != null;

		this.joinTableAnnotation = determineExplicitJoinTable( annotations );
	}

	public boolean isIgnoreNotFound() {
		return ignoreNotFound;
	}

	public String getReferencedEntityType() {
		return referencedEntityType;
	}

	public String getMappedBy() {
		return mappedBy;
	}

	public Set<CascadeType> getCascadeTypes() {
		return cascadeTypes;
	}

	public Set<org.hibernate.annotations.CascadeType> getHibernateCascadeTypes() {
		return hibernateCascadeTypes;
	}

	public boolean isOrphanRemoval() {
		return isOrphanRemoval;
	}

	public FetchStyle getFetchStyle() {
		return fetchStyle;
	}

	public String getReferencedIdAttributeName() {
		return referencedIdAttributeName;
	}

	public boolean mapsId() {
		return mapsId;
	}

	public List<Column> getJoinColumnValues() {
		return joinColumnValues;
	}

	public List<Column> getInverseJoinColumnValues() {
		return inverseJoinColumnValues;
	}

	public AnnotationInstance getJoinTableAnnotation() {
		return joinTableAnnotation;
	}

	@Override
	public AttributeTypeResolver getHibernateTypeResolver() {
		if ( resolver == null ) {
			resolver = getDefaultHibernateTypeResolver();
		}
		return resolver;
	}

	@Override
	public boolean isLazy() {
		return isLazy;
	}

	public boolean isUnWrapProxy() {
		return isUnWrapProxy;
	}

	@Override
	public boolean isOptional() {
		return isOptional;
	}

	@Override
	public boolean isInsertable() {
		return true;
	}

	@Override
	public boolean isUpdatable() {
		return true;
	}
	protected boolean hasOptimisticLockAnnotation(){
		AnnotationInstance optimisticLockAnnotation = JandexHelper.getSingleAnnotation(
				annotations(),
				HibernateDotNames.OPTIMISTIC_LOCK
		);
		return optimisticLockAnnotation != null;
	}
	@Override
	public boolean isOptimisticLockable() {
		if(hasOptimisticLockAnnotation()){
			return super.isOptimisticLockable();
		} else {
			Nature nature = getNature();
			return (nature != Nature.ONE_TO_ONE && nature != Nature.MANY_TO_ONE ) || isInsertable();
		}

	}


	@Override
	public PropertyGeneration getPropertyGeneration() {
		return PropertyGeneration.NEVER;
	}

	private AttributeTypeResolver getDefaultHibernateTypeResolver() {
		return new CompositeAttributeTypeResolver( this, new AttributeTypeResolverImpl( this ) );
	}

	private boolean determineNotFoundBehavior() {
		NotFoundAction action = NotFoundAction.EXCEPTION;
		AnnotationInstance notFoundAnnotation = JandexHelper.getSingleAnnotation(
				annotations(),
				HibernateDotNames.NOT_FOUND
		);
		if ( notFoundAnnotation != null ) {
			AnnotationValue actionValue = notFoundAnnotation.value( "action" );
			if ( actionValue != null ) {
				action = Enum.valueOf( NotFoundAction.class, actionValue.asEnum() );
			}
		}

		return NotFoundAction.IGNORE.equals( action );
	}

	private boolean determinIsUnwrapProxy() {
		AnnotationInstance lazyToOne = JandexHelper.getSingleAnnotation( annotations(), HibernateDotNames.LAZY_TO_ONE );
		if ( lazyToOne != null ) {
			return JandexHelper.getEnumValue( lazyToOne, "value", LazyToOneOption.class ) == LazyToOneOption.NO_PROXY;
		}
		return false;
	}

	private boolean determineOptionality(AnnotationInstance associationAnnotation) {
		boolean optional = true;

		AnnotationValue optionalValue = associationAnnotation.value( "optional" );
		if ( optionalValue != null ) {
			optional = optionalValue.asBoolean();
		}

		return optional;
	}

	private boolean determineOrphanRemoval(AnnotationInstance associationAnnotation) {
		boolean orphanRemoval = false;
		AnnotationValue orphanRemovalValue = associationAnnotation.value( "orphanRemoval" );
		if ( orphanRemovalValue != null ) {
			orphanRemoval = orphanRemovalValue.asBoolean();
		}
		return orphanRemoval;
	}

	protected boolean determineIsLazy(AnnotationInstance associationAnnotation) {
		FetchType fetchType = JandexHelper.getEnumValue( associationAnnotation, "fetch", FetchType.class );
		boolean lazy = fetchType == FetchType.LAZY;
		final AnnotationInstance lazyToOneAnnotation = JandexHelper.getSingleAnnotation(
				annotations(),
				HibernateDotNames.LAZY_TO_ONE
		);
		if ( lazyToOneAnnotation != null ) {
			LazyToOneOption option = JandexHelper.getEnumValue( lazyToOneAnnotation, "value", LazyToOneOption.class );
			lazy = option != LazyToOneOption.FALSE;
		}

		if ( associationAnnotation.value( "fetch" ) != null ) {
			lazy = FetchType.LAZY == fetchType;
		}
		final AnnotationInstance fetchAnnotation = JandexHelper.getSingleAnnotation(
				annotations(),
				HibernateDotNames.FETCH
		);
		if ( fetchAnnotation != null ) {
			lazy = JandexHelper.getEnumValue( fetchAnnotation, "value", FetchMode.class ) != FetchMode.JOIN;
		}
		if ( getFetchStyle() != null ) {
			lazy = getFetchStyle() != FetchStyle.JOIN;
		}
		return lazy;
	}

	private String determineReferencedEntityType(AnnotationInstance associationAnnotation, Class<?> referencedAttributeType) {
		// use the annotated attribute type as default target type
		String targetTypeName = null;

		// unless we have an explicit @Target
		AnnotationInstance targetAnnotation = JandexHelper.getSingleAnnotation(
				annotations(),
				HibernateDotNames.TARGET
		);
		if ( targetAnnotation != null ) {
			targetTypeName = targetAnnotation.value().asClass().name().toString();
		}

		AnnotationValue targetEntityValue = associationAnnotation.value( "targetEntity" );
		if ( targetEntityValue != null ) {
			targetTypeName = targetEntityValue.asClass().name().toString();
		}

		if( StringHelper.isEmpty( targetTypeName ) ) {
			if ( referencedAttributeType != null )  {
				targetTypeName = referencedAttributeType.getName();
			}
			else {
				throw getContext().makeMappingException( "Can't find the target type for this collection attribute: "+ getRole() );
			}
		}

		return targetTypeName;
	}

	private String determineMappedByAttributeName(AnnotationInstance associationAnnotation) {
		String mappedBy = null;
		AnnotationValue mappedByAnnotationValue = associationAnnotation.value( "mappedBy" );
		if ( mappedByAnnotationValue != null ) {
			mappedBy = mappedByAnnotationValue.asString();
		}

		return mappedBy;
	}

	private Set<CascadeType> determineCascadeTypes(AnnotationInstance associationAnnotation) {
		Set<CascadeType> cascadeTypes = new HashSet<CascadeType>();
		AnnotationValue cascadeValue = associationAnnotation.value( "cascade" );
		if ( cascadeValue != null ) {
			String[] cascades = cascadeValue.asEnumArray();
			for ( String s : cascades ) {
				cascadeTypes.add( Enum.valueOf( CascadeType.class, s ) );
			}
		}
		return cascadeTypes;
	}

	private Set<org.hibernate.annotations.CascadeType> determineHibernateCascadeTypes(
			Map<DotName, List<AnnotationInstance>> annotations) {
		AnnotationInstance cascadeAnnotation = JandexHelper
				.getSingleAnnotation(
						annotations, HibernateDotNames.CASCADE );
		Set<org.hibernate.annotations.CascadeType> cascadeTypes
				= new HashSet<org.hibernate.annotations.CascadeType>();
		if ( cascadeAnnotation != null ) {
			AnnotationValue cascadeValue = cascadeAnnotation.value();
			if ( cascadeValue != null ) {
				String[] cascades = cascadeValue.asEnumArray();
				for ( String s : cascades ) {
					cascadeTypes.add( Enum.valueOf(
							org.hibernate.annotations.CascadeType.class, s ) );
				}
			}
		}
		return cascadeTypes;
	}

	private FetchStyle determineFetchStyle() {
		AnnotationInstance fetchAnnotation = JandexHelper.getSingleAnnotation( annotations(), HibernateDotNames.FETCH );
		if ( fetchAnnotation != null ) {
			org.hibernate.annotations.FetchMode annotationFetchMode = JandexHelper.getEnumValue(
					fetchAnnotation,
					"value",
					org.hibernate.annotations.FetchMode.class
			);
			return EnumConversionHelper.annotationFetchModeToFetchStyle( annotationFetchMode );
		}

		return null;
	}

	private String determineMapsId() {
		AnnotationInstance mapsIdAnnotation = JandexHelper.getSingleAnnotation( annotations(), JPADotNames.MAPS_ID );
		if ( mapsIdAnnotation == null ) {
			return null;
		}
		if ( !( Nature.MANY_TO_ONE.equals( getNature() ) || Nature.MANY_TO_ONE
				.equals( getNature() ) ) ) {
			throw new MappingException(
					"@MapsId can only be specified on a many-to-one or one-to-one associations, property: "+ getRole(),
					getContext().getOrigin()
			);
		}
		return JandexHelper.getValue( mapsIdAnnotation, "value", String.class );
	}

	private List<Column> determineJoinColumnAnnotations(Map<DotName, List<AnnotationInstance>> annotations) {
		ArrayList<Column> joinColumns = new ArrayList<Column>();

		// single @JoinColumn
		AnnotationInstance joinColumnAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				JPADotNames.JOIN_COLUMN
		);
		if ( joinColumnAnnotation != null ) {
			joinColumns.add( new Column( joinColumnAnnotation ) );
		}

		// @JoinColumns
		AnnotationInstance joinColumnsAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				JPADotNames.JOIN_COLUMNS
		);
		if ( joinColumnsAnnotation != null ) {
			List<AnnotationInstance> columnsList = Arrays.asList(
					JandexHelper.getValue( joinColumnsAnnotation, "value", AnnotationInstance[].class )
			);
			for ( AnnotationInstance annotation : columnsList ) {
				joinColumns.add( new Column( annotation ) );
			}
		}

		// @JoinColumn as part of @CollectionTable
		AnnotationInstance collectionTableAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				JPADotNames.COLLECTION_TABLE
		);
		if(collectionTableAnnotation != null) {
			List<AnnotationInstance> columnsList = Arrays.asList(
					JandexHelper.getValue( collectionTableAnnotation, "joinColumns", AnnotationInstance[].class )
			);
			for ( AnnotationInstance annotation : columnsList ) {
				joinColumns.add( new Column( annotation ) );
			}
		}

		// @JoinColumn as part of @JoinTable
		AnnotationInstance joinTableAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				JPADotNames.JOIN_TABLE
		);
		if(joinTableAnnotation != null) {
			List<AnnotationInstance> columnsList = Arrays.asList(
					JandexHelper.getValue( joinTableAnnotation, "joinColumns", AnnotationInstance[].class )
			);
			for ( AnnotationInstance annotation : columnsList ) {
				joinColumns.add( new Column( annotation ) );
			}
		}

		joinColumns.trimToSize();
		return joinColumns;
	}

	private List<Column> determineInverseJoinColumnAnnotations(
			Map<DotName, List<AnnotationInstance>> annotations) {
		ArrayList<Column> inverseJoinColumns = new ArrayList<Column>();

		// @JoinColumn as part of @JoinTable
		AnnotationInstance joinTableAnnotation = JandexHelper
				.getSingleAnnotation( annotations, JPADotNames.JOIN_TABLE );
		if(joinTableAnnotation != null) {
			List<AnnotationInstance> columnsList = Arrays.asList(
					JandexHelper.getValue( joinTableAnnotation,
							"inverseJoinColumns", AnnotationInstance[].class )
			);
			for ( AnnotationInstance annotation : columnsList ) {
				inverseJoinColumns.add( new Column( annotation ) );
			}
		}

		inverseJoinColumns.trimToSize();
		return inverseJoinColumns;
	}

	private AnnotationInstance determineExplicitJoinTable(Map<DotName, List<AnnotationInstance>> annotations) {
		AnnotationInstance annotationInstance = null;
		AnnotationInstance collectionTableAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				JPADotNames.COLLECTION_TABLE
		);

		AnnotationInstance joinTableAnnotation = JandexHelper.getSingleAnnotation(
				annotations,
				JPADotNames.JOIN_TABLE
		);

		// sanity checks
		if ( collectionTableAnnotation != null && joinTableAnnotation != null ) {
			String msg = coreLogger.collectionTableAndJoinTableUsedTogether(
					getContext().getOrigin().getName(),
					getName()
			);
			throw new MappingException( msg, getContext().getOrigin() );
		}

		if ( collectionTableAnnotation != null ) {
			if ( JandexHelper.getSingleAnnotation( annotations, JPADotNames.ELEMENT_COLLECTION ) == null ) {
				String msg = coreLogger.collectionTableWithoutElementCollection(
						getContext().getOrigin().getName(),
						getName()
				);
				throw new MappingException( msg, getContext().getOrigin() );
			}
			annotationInstance = collectionTableAnnotation;
		}

		if ( joinTableAnnotation != null ) {
			if ( JandexHelper.getSingleAnnotation( annotations, JPADotNames.ONE_TO_ONE ) == null
					&& JandexHelper.getSingleAnnotation( annotations, JPADotNames.ONE_TO_MANY ) == null
					&& JandexHelper.getSingleAnnotation( annotations, JPADotNames.MANY_TO_MANY ) == null
					&& JandexHelper.getSingleAnnotation( annotations, JPADotNames.MANY_TO_ONE ) == null ) {
				String msg = coreLogger.joinTableForNonAssociationAttribute(
						getContext().getOrigin().getName(),
						getName()
				);
				throw new MappingException( msg, getContext().getOrigin() );
			}
			annotationInstance = joinTableAnnotation;
		}

		return annotationInstance;
	}
}


