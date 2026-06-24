/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.community.dialect;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.hibernate.ScrollMode;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.community.dialect.function.CUBRIDExtractFunction;
import org.hibernate.community.dialect.identity.CUBRIDIdentityColumnSupport;
import org.hibernate.community.dialect.sequence.CUBRIDSequenceSupport;
import org.hibernate.community.dialect.sequence.SequenceInformationExtractorCUBRIDDatabaseImpl;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.DmlTargetColumnQualifierSupport;
import org.hibernate.dialect.NationalizationSupport;
import org.hibernate.dialect.NullOrdering;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.TimeZoneSupport;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.lock.PessimisticLockStyle;
import org.hibernate.dialect.lock.internal.LockingSupportSimple;
import org.hibernate.dialect.lock.spi.ConnectionLockTimeoutStrategy;
import org.hibernate.dialect.lock.spi.LockTimeoutType;
import org.hibernate.dialect.lock.spi.LockingSupport;
import org.hibernate.dialect.lock.spi.OuterJoinLockingType;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.LimitLimitHandler;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.dialect.type.IntervalType;
import org.hibernate.dialect.type.MySQLCastingJsonArrayJdbcTypeConstructor;
import org.hibernate.dialect.type.MySQLCastingJsonJdbcType;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.IdentifierCaseStrategy;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;
import org.hibernate.engine.jdbc.env.spi.NameQualifierSupport;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.query.SemanticException;
import org.hibernate.query.common.TemporalUnit;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.jdbc.BlobJdbcType;
import org.hibernate.type.descriptor.jdbc.ClobJdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.BinaryFloatDdlType;
import org.hibernate.type.descriptor.sql.internal.CapacityDependentDdlType;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;
import org.hibernate.type.spi.TypeConfiguration;

import jakarta.persistence.TemporalType;

import static org.hibernate.query.common.TemporalUnit.HOUR;
import static org.hibernate.query.common.TemporalUnit.MINUTE;
import static org.hibernate.query.common.TemporalUnit.NANOSECOND;
import static org.hibernate.query.common.TemporalUnit.NATIVE;
import static org.hibernate.query.common.TemporalUnit.SECOND;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsDate;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsLocalTime;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTimestampWithMicros;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTimestampWithMillis;
import static org.hibernate.type.SqlTypes.BINARY;
import static org.hibernate.type.SqlTypes.BLOB;
import static org.hibernate.type.SqlTypes.BOOLEAN;
import static org.hibernate.type.SqlTypes.JSON;
import static org.hibernate.type.SqlTypes.TIME;
import static org.hibernate.type.SqlTypes.TIMESTAMP;
import static org.hibernate.type.SqlTypes.TIMESTAMP_WITH_TIMEZONE;
import static org.hibernate.type.SqlTypes.TIME_WITH_TIMEZONE;
import static org.hibernate.type.SqlTypes.TINYINT;
import static org.hibernate.type.SqlTypes.VARBINARY;

/**
 * An SQL dialect for CUBRID 10.2 and above.
 *
 * @author Seok Jeong Il
 */
public class CUBRIDDialect extends Dialect {

	private static final DatabaseVersion MINIMUM_VERSION = DatabaseVersion.make( 10, 2 );

	/**
	 * Constructs a CUBRIDDialect
	 */
	public CUBRIDDialect() {
		this( MINIMUM_VERSION );
	}

	public CUBRIDDialect(DatabaseVersion version) {
		super( version );
	}

	@Override
	protected DatabaseVersion getMinimumSupportedVersion() {
		return MINIMUM_VERSION;
	}

	@Override
	protected String columnType(int sqlTypeCode) {
		return switch ( sqlTypeCode ) {
			//CUBRID's 'bit' is a fixed-length bit string that rejects boolean host
			//variables, so map boolean to a numeric type instead
			case BOOLEAN -> "smallint";
			case TINYINT -> "smallint";
			//CUBRID's 'time' does not accept an explicit precision (e.g. time(0))
			case TIME -> "time";
			//'timestamp' has a very limited range
			//'datetime' does not support explicit precision
			//(always 3, millisecond precision)
			case TIMESTAMP -> "datetime";
			case TIME_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE -> "datetimetz";
			default -> super.columnType( sqlTypeCode );
		};
	}

	@Override
	protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.registerColumnTypes( typeContributions, serviceRegistry );
		final DdlTypeRegistry ddlTypeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

		ddlTypeRegistry.addDescriptor( new BinaryFloatDdlType( this ) );

		//CUBRID has native JSON support
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( JSON, "json", this ) );

		//CUBRID has no 'binary' nor 'varbinary', but 'bit' is
		//intended to be used for binary data (unfortunately the
		//length parameter is measured in bits, not bytes)
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( BINARY, "bit($l)", this ) );
		ddlTypeRegistry.addDescriptor(
				CapacityDependentDdlType.builder(
								VARBINARY,
								CapacityDependentDdlType.LobKind.BIGGEST_LOB,
								columnType( BLOB ),
								this
						)
						.withTypeCapacity( getMaxVarbinaryLength(), "bit varying($l)" )
						.build()
		);
	}

	@Override
	protected void registerDefaultKeywords() {
		super.registerDefaultKeywords();
		registerKeyword( "TYPE" );
		registerKeyword( "YEAR" );
		registerKeyword( "MONTH" );
		registerKeyword( "ALIAS" );
		registerKeyword( "VALUE" );
		registerKeyword( "FIRST" );
		registerKeyword( "ROLE" );
		registerKeyword( "CLASS" );
		registerKeyword( "BIT" );
		registerKeyword( "TIME" );
		registerKeyword( "QUERY" );
		registerKeyword( "DATE" );
		registerKeyword( "USER" );
		registerKeyword( "ACTION" );
		registerKeyword( "SYS_USER" );
		registerKeyword( "ZONE" );
		registerKeyword( "LANGUAGE" );
		registerKeyword( "DICTIONARY" );
		registerKeyword( "DATA" );
		registerKeyword( "TEST" );
		registerKeyword( "SUPERCLASS" );
		registerKeyword( "SECTION" );
		registerKeyword( "LOWER" );
		registerKeyword( "LIST" );
		registerKeyword( "OID" );
		registerKeyword( "DAY" );
		registerKeyword( "IF" );
		registerKeyword( "ATTRIBUTE" );
		registerKeyword( "STRING" );
		registerKeyword( "SEARCH" );
		registerKeyword( "POSITION" );
		registerKeyword( "NAMES" );
		registerKeyword( "LAST" );
		registerKeyword( "DEPTH" );
		//JSON is a reserved word in CUBRID; register it so a column literally named "json" is quoted
		registerKeyword( "JSON" );
	}

	public CUBRIDDialect(DialectResolutionInfo info) {
		super( info );
	}

	@Override
	public int getDefaultStatementBatchSize() {
		return 15;
	}

	@Override
	public int getMaxVarcharLength() {
		return 1_073_741_823;
	}

	@Override
	public int getMaxVarbinaryLength() {
		//note that the length of BIT VARYING in CUBRID is actually in bits
		return 1_073_741_823;
	}

	@Override
	public JdbcType resolveSqlTypeDescriptor(
			String columnTypeName,
			int jdbcTypeCode,
			int precision,
			int scale,
			JdbcTypeRegistry jdbcTypeRegistry) {
		if ( jdbcTypeCode == Types.BIT ) {
			return jdbcTypeRegistry.getDescriptor( Types.BOOLEAN );
		}
		return super.resolveSqlTypeDescriptor(
				columnTypeName,
				jdbcTypeCode,
				precision,
				scale,
				jdbcTypeRegistry
		);
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		//CUBRID has no native boolean; store as smallint
		return Types.SMALLINT;
	}

	//not used for anything right now, but it
	//could be used for timestamp literal format
	@Override
	public int getDefaultTimestampPrecision() {
		return 3;
	}

	@Override
	public int getFloatPrecision() {
		return 21; // -> 7 decimal digits
	}

	@Override
	public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.contributeTypes( typeContributions, serviceRegistry );
		final JdbcTypeRegistry jdbcTypeRegistry = typeContributions.getTypeConfiguration().getJdbcTypeRegistry();
		//the CUBRID JDBC driver has no stream-based LOB binding, so materialize
		//BLOB/CLOB to byte[]/String (setBytes/setString) instead
		jdbcTypeRegistry.addDescriptor( Types.BLOB, BlobJdbcType.MATERIALIZED );
		jdbcTypeRegistry.addDescriptor( Types.CLOB, ClobJdbcType.MATERIALIZED );
		jdbcTypeRegistry.addDescriptor( Types.NCLOB, ClobJdbcType.MATERIALIZED );
		//CUBRID has native JSON support with a MySQL-compatible cast(? as json) write expression
		jdbcTypeRegistry.addDescriptorIfAbsent( JSON, MySQLCastingJsonJdbcType.INSTANCE );
		jdbcTypeRegistry.addTypeConstructorIfAbsent( MySQLCastingJsonArrayJdbcTypeConstructor.INSTANCE );
	}

	@Override
	public boolean useInputStreamToInsertBlob() {
		//the CUBRID JDBC driver has no stream-based LOB binding
		return false;
	}

	@Override
	public boolean useConnectionToCreateLob() {
		//the CUBRID JDBC driver does not support Connection.createBlob()/createClob()
		return false;
	}

	@Override
	public boolean getDefaultNonContextualLobCreation() {
		return true;
	}

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		//CUBRID has no stable JDBC error codes for constraint violations, so match on the message
		return (sqlException, message, sql) -> {
			final String errorMessage = sqlException.getMessage();
			if ( errorMessage != null ) {
				if ( errorMessage.contains( "unique constraint" ) ) {
					return new ConstraintViolationException( message, sqlException, sql,
							ConstraintViolationException.ConstraintKind.UNIQUE,
							getViolatedConstraintNameExtractor().extractConstraintName( sqlException ) );
				}
				if ( errorMessage.contains( "foreign key" ) ) {
					return new ConstraintViolationException( message, sqlException, sql,
							ConstraintViolationException.ConstraintKind.FOREIGN_KEY,
							getViolatedConstraintNameExtractor().extractConstraintName( sqlException ) );
				}
				if ( errorMessage.contains( "NOT NULL constraint" ) ) {
					return new ConstraintViolationException( message, sqlException, sql,
							ConstraintViolationException.ConstraintKind.NOT_NULL,
							getViolatedConstraintNameExtractor().extractConstraintName( sqlException ) );
				}
			}
			return null;
		};
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle -> {
				final String message = sqle.getMessage();
				if ( message == null ) {
					return null;
				}
				if ( message.contains( "unique constraint" ) ) {
					return TemplatedViolatedConstraintNameExtractor.extractUsingTemplate( "INDEX ", "(", message );
				}
				if ( message.contains( "foreign key '" ) ) {
					return TemplatedViolatedConstraintNameExtractor.extractUsingTemplate( "foreign key '", "'", message );
				}
				return null;
			} );

	@Override
	public ScrollMode defaultScrollMode() {
		//the CUBRID JDBC driver has no scroll-insensitive cursor; only forward-only is supported
		return ScrollMode.FORWARD_ONLY;
	}

	@Override
	public boolean supportsJoinsInDelete() {
		//CUBRID supports multi-table/joined DELETE (e.g. DELETE c FROM t c JOIN ... )
		return true;
	}

	@Override
	public boolean supportsFromClauseInUpdate() {
		//CUBRID supports multi-table/joined UPDATE (e.g. UPDATE t c JOIN ... SET ... )
		return true;
	}

	@Override
	public DmlTargetColumnQualifierSupport getDmlTargetColumnQualifierSupport() {
		//joined DELETE/UPDATE require the table alias to qualify columns
		return DmlTargetColumnQualifierSupport.TABLE_ALIAS;
	}

	@Override
	public boolean supportsLateral() {
		//CUBRID supports correlated derived tables in the from clause (implicit lateral);
		//CUBRIDSqlAstTranslator renders them without the (unsupported) LATERAL keyword
		return true;
	}

	@Override
	public NameQualifierSupport getNameQualifierSupport() {
		return getVersion().isSameOrAfter( 11, 2 ) ? NameQualifierSupport.SCHEMA : NameQualifierSupport.NONE;
	}

	@Override
	public NationalizationSupport getNationalizationSupport() {
		//CUBRID has no nvarchar/nclob types; map nationalized types to the regular varchar/clob
		return NationalizationSupport.IMPLICIT;
	}

	@Override
	public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData metadata)
			throws SQLException {
		//CUBRID folds ALL identifiers (quoted or not) to lower case, but the JDBC driver only
		//reports this for unquoted identifiers (storesLowerCaseQuotedIdentifiers() is false), so
		//schema validation fails to match a quoted/mixed-case mapped name against the lower-case
		//catalog. Force lower-case folding for both to align with the actual engine behaviour.
		builder.setUnquotedCaseStrategy( IdentifierCaseStrategy.LOWER );
		builder.setQuotedCaseStrategy( IdentifierCaseStrategy.LOWER );
		//CUBRID rejects '$' in an unquoted identifier; Hibernate emits implicit '$'-bearing names
		//(e.g. bytecode-enhancement / nested-class-derived column, table and sequence names), so
		//force-quote any identifier containing '$'
		builder.setAutoQuoteDollar( true );
		return super.buildIdentifierHelper( builder, metadata );
	}

	@Override
	public void initializeFunctionRegistry(FunctionContributions functionContributions) {
		super.initializeFunctionRegistry(functionContributions);

		CommonFunctionFactory functionFactory = new CommonFunctionFactory(functionContributions);
		functionFactory.trim2();
		functionFactory.space();
		functionFactory.reverse();
		functionFactory.repeat();
		functionFactory.crc32();
		functionFactory.cot();
		functionFactory.log2();
		functionFactory.log10();
		functionFactory.pi();
		//rand() returns an integer between 0 and 2^31 on CUBRID
//		functionFactory.rand();
		functionFactory.radians();
		functionFactory.degrees();
		functionFactory.systimestamp();
		//TODO: CUBRID also has systime()/sysdate() returning TIME/DATE
		functionFactory.localtimeLocaltimestamp();
		functionFactory.hourMinuteSecond();
		functionFactory.yearMonthDay();
		functionFactory.dayofweekmonthyear();
		functionFactory.lastDay();
		functionFactory.weekQuarter();
		functionFactory.octetLength();
		functionFactory.bitLength();
		functionFactory.md5();
		functionFactory.trunc();
		functionFactory.toCharNumberDateTimestamp();
		functionFactory.substr();
		//also natively supports ANSI-style substring()
		functionFactory.instr();
		functionFactory.translate();
		functionFactory.ceiling_ceil();
		functionFactory.sha1();
		functionFactory.sha2();
		functionFactory.ascii();
		functionFactory.char_chr();
		functionFactory.position();
//		functionFactory.concat_pipeOperator();
		functionFactory.insert();
		functionFactory.nowCurdateCurtime();
		functionFactory.makedateMaketime();
		//CUBRID has no bit_and/bit_or/bit_xor/bit_not SQL functions; use the &|^~ operators instead
		functionFactory.bitandorxornot_operator();
		functionFactory.median();
		functionFactory.stddev();
		functionFactory.stddevPopSamp();
		functionFactory.variance();
		functionFactory.varPopSamp();
		functionFactory.datediff();
		functionFactory.adddateSubdateAddtimeSubtime();
		functionFactory.addMonths();
		functionFactory.monthsBetween();
		functionFactory.rownumInstOrderbyGroupbyNum();
		//CUBRID's regexp operator is MySQL-compatible (case-insensitive by default,
		//"regexp binary" for case-sensitive) and is a logical predicate, so render
		//regexp_like via the operator instead of a non-logical regexp_like() function
		//call, which CUBRID rejects in boolean contexts on 11.2+
		functionFactory.regexpLike_regexp();

		//CUBRID supports SQL window functions (OVER); register them so the Criteria path can
		//resolve row_number/rank/dense_rank/lag/lead/first_value/last_value/nth_value etc.
		functionFactory.windowFunctions();
		functionFactory.hypotheticalOrderedSetAggregates_windowEmulation();

		final SqmFunctionRegistry functionRegistry = functionContributions.getFunctionRegistry();
		final TypeConfiguration typeConfiguration = functionContributions.getTypeConfiguration();

		//CUBRID rejects extract(millisecond from <time>), so extract(second from <time>) must omit
		//the millisecond term that the default SECOND pattern adds
		functionRegistry.register( "extract", new CUBRIDExtractFunction( this, typeConfiguration ) );

		//the base maps local_time to CUBRID's localtime, but CUBRID's localtime is a TIMESTAMP
		//(datetime), so time=local_time comparisons fail; render it as current_time (a real TIME)
		functionRegistry.noArgsBuilder( "local_time", "current_time" )
				.setInvariantType( typeConfiguration.getBasicTypeRegistry().resolve( StandardBasicTypes.LOCAL_TIME ) )
				.setUseParenthesesWhenNoArgs( false )
				.register();
	}

	@Override
	public boolean supportsColumnCheck() {
		return false;
	}

	@Override
	public boolean supportsTableCheck() {
		return false;
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return CUBRIDSequenceSupport.INSTANCE;
	}

	@Override
	public String getDropForeignKeyString() {
		return "drop foreign key";
	}

	@Override
	public String getDropUniqueKeyString() {
		return "drop index";
	}

	@Override
	public boolean qualifyIndexName() {
		return false;
	}

	@Override
	public boolean supportsExistsInSelect() {
		return false;
	}

	@Override
	public String getQuerySequencesString() {
		return "select * from db_serial";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceInformationExtractorCUBRIDDatabaseImpl.INSTANCE;
	}

	// Use the inherited ANSI double-quote for quoted identifiers. CUBRID accepts "..." (as well as
	// [...] and backticks), and the ANSI form is what portable mappings/tests expect; the previous
	// '['/']' (SQL-Server style) produced be1_0.[col] which mismatched those expectations.

	private static final LockingSupport LOCKING_SUPPORT = new LockingSupportSimple(
			PessimisticLockStyle.CLAUSE,
			LockTimeoutType.NONE,
			OuterJoinLockingType.FULL,
			ConnectionLockTimeoutStrategy.NONE
	);

	@Override
	public LockingSupport getLockingSupport() {
		return LOCKING_SUPPORT;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select now()";
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public boolean supportsIfExistsBeforeTableName() {
		return true;
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return false;
	}

	@Override
	public boolean supportsOffsetInSubquery() {
		return true;
	}

	@Override
	public boolean supportsTemporaryTables() {
		return false;
	}

	@Override
	public boolean supportsWindowFunctions() {
		return true;
	}

	@Override
	public boolean supportsWithClauseInSubquery() {
		return true;
	}

	@Override
	public boolean supportsNestedWithClause() {
		//pinned false: derives from supportsWithClauseInSubquery(), but CUBRID rejects a with clause nested in another CTE
		return false;
	}

	@Override
	public boolean supportsRecursiveCTE() {
		return true;
	}

	@Override
	public boolean supportsNonQueryWithCTE() {
		return true;
	}

	@Override
	public boolean supportsValuesList() {
		return true;
	}

	@Override
	public boolean supportsAlterColumnType() {
		return true;
	}

	//CUBRID cannot change only the column type, so emit the full column definition
	@Override
	public String getAlterColumnTypeString(String columnName, String columnType, String columnDefinition) {
		return "modify column " + columnName + " " + columnDefinition.trim();
	}

	@Override
	public boolean canCreateSchema() {
		return false;
	}

	@Override
	public int getMaxIdentifierLength() {
		return 254;
	}

	@Override
	public NullOrdering getNullOrdering() {
		return NullOrdering.SMALLEST;
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new CUBRIDSqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	@Override
	public LimitHandler getLimitHandler() {
		return LimitLimitHandler.INSTANCE;
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return CUBRIDIdentityColumnSupport.INSTANCE;
	}

	@Override
	public boolean supportsPartitionBy() {
		return true;
	}

	@Override
	public void appendDatetimeFormat(SqlAppender appender, String format) {
		//I do not know if CUBRID supports FM, but it
		//seems that it does pad by default, so it needs it!
		appender.appendSql(
				OracleDialect.datetimeFormat( format, true, false )
				.replace("SSSSSS", "FF")
				.replace("SSSSS", "FF")
				.replace("SSSS", "FF")
				.replace("SSS", "FF")
				.replace("SS", "FF")
				.replace("S", "FF")
				.result()
		);
	}

	@Override
	public long getFractionalSecondPrecisionInNanos() {
		return 1_000_000; //milliseconds
	}

	//CUBRID's SQL parser does not understand the JDBC/ODBC escape literal syntax ({d '..'},
	//{t '..'}, {ts '..'}) that the base dialect emits, so render native date/time/datetime literals
	//instead (mirrors MySQLDialect). TIMESTAMP uses the 'datetime' keyword to match columnType(TIMESTAMP)
	//and because CUBRID's 'timestamp' literal rejects fractional seconds.
	@Override
	public void appendDateTimeLiteral(
			SqlAppender appender,
			TemporalAccessor temporalAccessor,
			TemporalType precision,
			TimeZone jdbcTimeZone) {
		switch ( precision ) {
			case DATE:
				appender.appendSql( "date '" );
				appendAsDate( appender, temporalAccessor );
				appender.appendSql( '\'' );
				break;
			case TIME:
				appender.appendSql( "time '" );
				appendAsLocalTime( appender, temporalAccessor );
				appender.appendSql( '\'' );
				break;
			case TIMESTAMP:
				if ( temporalAccessor instanceof ZonedDateTime zonedDateTime ) {
					temporalAccessor = zonedDateTime.toOffsetDateTime();
				}
				appender.appendSql( "datetime '" );
				appendAsTimestampWithMicros( appender, temporalAccessor, supportsTemporalLiteralOffset(), jdbcTimeZone, false );
				appender.appendSql( '\'' );
				break;
			default:
				throw new IllegalArgumentException();
		}
	}

	@Override
	public void appendDateTimeLiteral(SqlAppender appender, Date date, TemporalType precision, TimeZone jdbcTimeZone) {
		switch ( precision ) {
			case DATE:
				appender.appendSql( "date '" );
				appendAsDate( appender, date );
				appender.appendSql( '\'' );
				break;
			case TIME:
				appender.appendSql( "time '" );
				appendAsLocalTime( appender, date );
				appender.appendSql( '\'' );
				break;
			case TIMESTAMP:
				appender.appendSql( "datetime '" );
				appendAsTimestampWithMicros( appender, date, jdbcTimeZone );
				appender.appendSql( '\'' );
				break;
			default:
				throw new IllegalArgumentException();
		}
	}

	@Override
	public void appendDateTimeLiteral(SqlAppender appender, Calendar calendar, TemporalType precision, TimeZone jdbcTimeZone) {
		switch ( precision ) {
			case DATE:
				appender.appendSql( "date '" );
				appendAsDate( appender, calendar );
				appender.appendSql( '\'' );
				break;
			case TIME:
				appender.appendSql( "time '" );
				appendAsLocalTime( appender, calendar );
				appender.appendSql( '\'' );
				break;
			case TIMESTAMP:
				appender.appendSql( "datetime '" );
				appendAsTimestampWithMillis( appender, calendar, jdbcTimeZone );
				appender.appendSql( '\'' );
				break;
			default:
				throw new IllegalArgumentException();
		}
	}

	/**
	 * CUBRID supports a limited list of temporal fields in the
	 * extract() function, but we can emulate some of them by
	 * using the appropriate named functions instead of
	 * extract().
	 *
	 * Thus, the additional supported fields are
	 * {@link TemporalUnit#DAY_OF_YEAR},
	 * {@link TemporalUnit#DAY_OF_MONTH},
	 * {@link TemporalUnit#DAY_OF_YEAR}.
	 *
	 * In addition, the field {@link TemporalUnit#SECOND} is
	 * redefined to include milliseconds.
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		return switch (unit) {
			case SECOND -> "(second(?2)+extract(millisecond from ?2)/1e3)";
			case DAY_OF_WEEK -> "dayofweek(?2)";
			case DAY_OF_MONTH ->"dayofmonth(?2)";
			case DAY_OF_YEAR -> "dayofyear(?2)";
			case WEEK -> "week(?2,3)"; //mode 3 is the ISO week
			default -> "?1(?2)";
		};
	}

	@Override
	public TimeZoneSupport getTimeZoneSupport() {
		//the CUBRID JDBC driver has no java.time support, so route temporal binding
		//through java.sql.Timestamp by normalizing to the JDBC timezone
		return TimeZoneSupport.NORMALIZE;
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType, IntervalType intervalType) {
		return switch (unit) {
			case NANOSECOND -> "adddate(?3,interval (?2)/1e6 millisecond)";
			case NATIVE -> "adddate(?3,interval ?2 millisecond)";
			default -> "adddate(?3,interval ?2 ?1)";
		};
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		StringBuilder pattern = new StringBuilder();
		switch ( unit ) {
			case DAY:
				//note: datediff() is backwards on CUBRID
				return "datediff(?3,?2)";
			//CUBRID has no timestampdiff() builtin; emulate whole-unit counts with component
			//arithmetic (year()/month()/datediff()), as PostgreSQLDialect does
			case YEAR:
				return "(year(?3)-year(?2))";
			case MONTH:
				return "((year(?3)-year(?2))*12+(month(?3)-month(?2)))";
			case QUARTER:
				return "(((year(?3)-year(?2))*12+(month(?3)-month(?2)))/3)";
			case WEEK:
				//note: datediff() is backwards on CUBRID
				return "(datediff(?3,?2)/7)";
			case HOUR:
				timediff(pattern, HOUR, unit);
				break;
			case MINUTE:
				pattern.append("(");
				timediff(pattern, MINUTE, unit);
				pattern.append("+");
				timediff(pattern, HOUR, unit);
				pattern.append(")");
				break;
			case SECOND:
				pattern.append("(");
				timediff(pattern, SECOND, unit);
				pattern.append("+");
				timediff(pattern, MINUTE, unit);
				pattern.append("+");
				timediff(pattern, HOUR, unit);
				pattern.append(")");
				break;
			case NATIVE:
			case NANOSECOND:
				pattern.append("(");
				timediff(pattern, unit, unit);
				pattern.append("+");
				timediff(pattern, SECOND, unit);
				pattern.append("+");
				timediff(pattern, MINUTE, unit);
				pattern.append("+");
				timediff(pattern, HOUR, unit);
				pattern.append(")");
				break;
			default:
				throw new SemanticException("unsupported temporal unit for CUBRID: " + unit);
		}
		return pattern.toString();
	}

	private void timediff(
			StringBuilder sqlAppender,
			TemporalUnit diffUnit,
			TemporalUnit toUnit) {
		if ( diffUnit == NANOSECOND ) {
			sqlAppender.append("1e6*");
		}
		sqlAppender.append("extract(");
		if ( diffUnit == NANOSECOND || diffUnit == NATIVE ) {
			sqlAppender.append("millisecond");
		}
		else {
			sqlAppender.append("?1");
		}
		//note: timediff() is backwards on CUBRID; CUBRID extract requires 'from', not a comma
		sqlAppender.append(" from timediff(?3,?2))");
		sqlAppender.append( diffUnit.conversionFactor( toUnit, this ) );
	}

	@Override
	public String getDual() {
		//TODO: is this really needed?
		//TODO: would "from table({0})" be better?
		return "db_root";
	}

	@Override
	public String getFromDualForSelectOnly() {
		return " from " + getDual();
	}

	@Override
	public boolean supportsRowValueConstructorSyntax() {
		return true;
	}

	@Override
	public boolean supportsRowValueConstructorGtLtSyntax() {
		return false;
	}

	@Override
	public boolean supportsRowValueConstructorSyntaxInQuantifiedPredicates() {
		return false;
	}

	@Override
	public boolean supportsRowValueConstructorSyntaxInInList() {
		return true;
	}

	@Override
	public boolean supportsRowValueConstructorSyntaxInInSubQuery() {
		return false;
	}

}
