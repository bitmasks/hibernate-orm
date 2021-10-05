/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.type.descriptor.jdbc;

import org.hibernate.type.descriptor.java.JavaTypeDescriptor;

/**
 * Extension contract for JdbcTypeDescriptor implementations that understand how to
 * adjust themselves relative to where/how they are used (e.g. accounting
 * for LOB, nationalized, primitive/wrapper, etc).
 *
 * @author Christian Beikov
 */
public interface AdjustableJdbcTypeDescriptor extends JdbcTypeDescriptor {

	/**
	 * Perform the adjustment
	 */
	JdbcTypeDescriptor resolveIndicatedType(JdbcTypeDescriptorIndicators indicators, JavaTypeDescriptor<?> domainJtd);
}
