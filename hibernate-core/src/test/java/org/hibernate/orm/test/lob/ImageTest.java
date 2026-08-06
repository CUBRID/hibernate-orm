/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.lob;

import org.hibernate.community.dialect.CUBRIDDialect;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SkipForDialect;
import org.junit.jupiter.api.Test;

/**
 * Tests eager materialization and mutation of data mapped by
 * {@link org.hibernate.type.StandardBasicTypes#IMAGE}.
 *
 * @author Gail Badner
 */
@DomainModel(xmlMappings = "org/hibernate/orm/test/lob/ImageMappings.hbm.xml")
public class ImageTest extends LongByteArrayTest {

	@Test
	@Override
	@SkipForDialect(dialectClass = CUBRIDDialect.class, reason = "CUBRID rejects the byte[] host variable Hibernate binds here (Cannot coerce host var to type bit varying)")
	public void testSaving(SessionFactoryScope scope) {
		super.testSaving( scope );
	}
}
