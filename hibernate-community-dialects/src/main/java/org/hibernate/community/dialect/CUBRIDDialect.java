/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.community.dialect;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.ScrollMode;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
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
import org.hibernate.dialect.type.IntervalType;
import org.hibernate.query.common.TemporalUnit;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.descriptor.jdbc.BlobJdbcType;
import org.hibernate.type.descriptor.jdbc.ClobJdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.BinaryFloatDdlType;
import org.hibernate.type.descriptor.sql.internal.CapacityDependentDdlType;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;

import jakarta.persistence.TemporalType;

import static org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor.extractUsingTemplate;
import static org.hibernate.internal.util.JdbcExceptionHelper.extractErrorCode;
import static org.hibernate.query.common.TemporalUnit.HOUR;
import static org.hibernate.query.common.TemporalUnit.MINUTE;
import static org.hibernate.query.common.TemporalUnit.NANOSECOND;
import static org.hibernate.query.common.TemporalUnit.NATIVE;
import static org.hibernate.query.common.TemporalUnit.SECOND;
import static org.hibernate.type.SqlTypes.BINARY;
import static org.hibernate.type.SqlTypes.BLOB;
import static org.hibernate.type.SqlTypes.BOOLEAN;
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
	}

	@Override
	public boolean useInputStreamToInsertBlob()	 {
		// the CUBRID JDBC driver has no stream-based LOB binding
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
		//CUBRID exposes no SQLState for constraint violations, so classify on the server error code
		return (sqlException, message, sql) -> switch ( extractErrorCode( sqlException ) ) {
			case -670, -886, -564 -> new ConstraintViolationException( message, sqlException, sql,
					ConstraintViolationException.ConstraintKind.UNIQUE,
					getViolatedConstraintNameExtractor().extractConstraintName( sqlException ) );
			case -922, -924 -> new ConstraintViolationException( message, sqlException, sql,
					ConstraintViolationException.ConstraintKind.FOREIGN_KEY,
					getViolatedConstraintNameExtractor().extractConstraintName( sqlException ) );
			case -631, -225 -> new ConstraintViolationException( message, sqlException, sql,
					ConstraintViolationException.ConstraintKind.NOT_NULL,
					getViolatedConstraintNameExtractor().extractConstraintName( sqlException ) );
			default -> null;
		};
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	//the constraint name is only in the message text, so parse it out by template (English, best-effort)
	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle -> switch ( extractErrorCode( sqle ) ) {
				case -670, -886, -564 -> extractUsingTemplate( "INDEX ", "(", sqle.getMessage() );
				case -922, -924 -> extractUsingTemplate( "foreign key '", "'", sqle.getMessage() );
				default -> null;
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
		return true;
	}

	@Override
	public NameQualifierSupport getNameQualifierSupport() {
		return NameQualifierSupport.NONE;
	}

	@Override
	public NationalizationSupport getNationalizationSupport() {
		//CUBRID has no nvarchar/nclob types; map nationalized types to the regular varchar/clob
		return NationalizationSupport.IMPLICIT;
	}

	@Override
	public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData metadata)
			throws SQLException {
		builder.setUnquotedCaseStrategy( IdentifierCaseStrategy.LOWER );
		builder.setQuotedCaseStrategy( IdentifierCaseStrategy.LOWER );
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
		functionFactory.bitandorxornot_bitAndOrXorNot();
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
		functionFactory.regexpLike_regexp();
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

	@Override
	public char openQuote() {
		return '[';
	}

	@Override
	public char closeQuote() {
		return ']';
	}

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
		//note: timediff() is backwards on CUBRID
		sqlAppender.append(",timediff(?3,?2))");
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
