package liquibase.parser.core.formattedsql;

import liquibase.Labels;
import liquibase.Scope;
import liquibase.change.core.EmptyChange;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.ChangeLogParseException;
import liquibase.parser.ChangeLogParser;
import liquibase.precondition.core.PreconditionContainer;
import liquibase.precondition.core.SqlPrecondition;
import liquibase.resource.ResourceAccessor;
import liquibase.util.FileUtil;
import liquibase.util.StreamUtil;
import liquibase.util.StringUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("java:S2583")
public class FormattedSqlChangeLogParser implements ChangeLogParser {

    @Override
    public boolean supports(String changeLogFile, ResourceAccessor resourceAccessor) {
        BufferedReader reader = null;
        try {
            if (changeLogFile.endsWith(".sql")) {
                InputStream fileStream = openChangeLogFile(changeLogFile, resourceAccessor);
                if (fileStream == null) {
                    return false;
                }
                reader = new BufferedReader(StreamUtil.readStreamWithReader(fileStream, null));

                String firstLine = reader.readLine();

                while (firstLine.trim().isEmpty() && reader.ready()) {
                    firstLine = reader.readLine();
                }

                return (firstLine != null) && firstLine.matches("\\-\\-\\s*liquibase formatted.*");
            } else {
                return false;
            }
        } catch (IOException e) {
            Scope.getCurrentScope().getLog(getClass()).fine("Exception reading " + changeLogFile, e);
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Scope.getCurrentScope().getLog(getClass()).fine("Exception closing " + changeLogFile, e);
                }
            }
        }
    }

    @Override
    public int getPriority() {
        return PRIORITY_DEFAULT + 5;
    }

    @Override
    public DatabaseChangeLog parse(String physicalChangeLogLocation, ChangeLogParameters changeLogParameters, ResourceAccessor resourceAccessor) throws ChangeLogParseException {

        DatabaseChangeLog changeLog = new DatabaseChangeLog();
        changeLog.setChangeLogParameters(changeLogParameters);

        changeLog.setPhysicalFilePath(physicalChangeLogLocation);

        try (BufferedReader reader = new BufferedReader(StreamUtil.readStreamWithReader(openChangeLogFile(physicalChangeLogLocation, resourceAccessor), null))) {
            StringBuilder currentSql = new StringBuilder();
            StringBuilder currentRollbackSql = new StringBuilder();

            ChangeSet changeSet = null;
            RawSQLChange change = null;
            Pattern changeLogPattern = Pattern.compile("\\-\\-\\s*liquibase formatted.*", Pattern.CASE_INSENSITIVE);
            Pattern changeSetPattern = Pattern.compile("\\s*\\-\\-[\\s]*changeset\\s+(\"[^\"]+\"|[^:]+):(\"[^\"]+\"|\\S+).*", Pattern.CASE_INSENSITIVE);
            Pattern rollbackPattern = Pattern.compile("\\s*\\-\\-[\\s]*rollback (.*)", Pattern.CASE_INSENSITIVE);
            Pattern preconditionsPattern = Pattern.compile("\\s*\\-\\-[\\s]*preconditions(.*)", Pattern.CASE_INSENSITIVE);
            Pattern preconditionPattern = Pattern.compile("\\s*\\-\\-[\\s]*precondition\\-([a-zA-Z0-9-]+) (.*)", Pattern.CASE_INSENSITIVE);
            Pattern stripCommentsPattern = Pattern.compile(".*stripComments:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern splitStatementsPattern = Pattern.compile(".*splitStatements:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern rollbackSplitStatementsPattern = Pattern.compile(".*rollbackSplitStatements:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern endDelimiterPattern = Pattern.compile(".*endDelimiter:(\\S*).*", Pattern.CASE_INSENSITIVE);
            Pattern rollbackEndDelimiterPattern = Pattern.compile(".*rollbackEndDelimiter:(\\S*).*", Pattern.CASE_INSENSITIVE);
            Pattern commentPattern = Pattern.compile("\\-\\-[\\s]*comment:? (.*)", Pattern.CASE_INSENSITIVE);
            Pattern validCheckSumPattern = Pattern.compile("\\-\\-[\\s]*validCheckSum:? (.*)", Pattern.CASE_INSENSITIVE);
            Pattern ignoreLinesPattern = Pattern.compile("\\-\\-[\\s]*ignoreLines:(\\w+)", Pattern.CASE_INSENSITIVE);
            Pattern runWithPattern = Pattern.compile(".*runWith:(\\w+).*", Pattern.CASE_INSENSITIVE);

            Pattern runOnChangePattern = Pattern.compile(".*runOnChange:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern runAlwaysPattern = Pattern.compile(".*runAlways:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern contextPattern = Pattern.compile(".*context:(\".*\"|\\S*).*", Pattern.CASE_INSENSITIVE);
            Pattern logicalFilePathPattern = Pattern.compile(".*logicalFilePath:(\\S*).*", Pattern.CASE_INSENSITIVE);
            Pattern changeLogIdPattern = Pattern.compile(".*changeLogId:(\\S*).*", Pattern.CASE_INSENSITIVE);
            Pattern labelsPattern = Pattern.compile(".*labels:(\\S*).*", Pattern.CASE_INSENSITIVE);
            Pattern runInTransactionPattern = Pattern.compile(".*runInTransaction:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern dbmsPattern = Pattern.compile(".*dbms:([^,][\\w!,]+).*", Pattern.CASE_INSENSITIVE);
            Pattern failOnErrorPattern = Pattern.compile(".*failOnError:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern onFailPattern = Pattern.compile(".*onFail:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern onErrorPattern = Pattern.compile(".*onError:(\\w+).*", Pattern.CASE_INSENSITIVE);
            Pattern onUpdateSqlPattern = Pattern.compile(".*onUpdateSQL:(\\w+).*", Pattern.CASE_INSENSITIVE);

            Matcher rollbackSplitStatementsPatternMatcher=null;
            boolean rollbackSplitStatements = true;
            String rollbackEndDelimiter = null;

            String line;
            while ((line = reader.readLine()) != null) {

                Matcher changeLogPatterMatcher = changeLogPattern.matcher (line);
                if (changeLogPatterMatcher.matches ()) {
                   Matcher logicalFilePathMatcher = logicalFilePathPattern.matcher (line);
                   changeLog.setLogicalFilePath (parseString(logicalFilePathMatcher));

                    Matcher changeLogIdMatcher = changeLogIdPattern.matcher (line);
                    changeLog.setChangeLogId (parseString(changeLogIdMatcher));
                }

                Matcher ignoreLinesMatcher = ignoreLinesPattern.matcher(line);
                if (ignoreLinesMatcher.matches ()) {
                    if ("start".equals(ignoreLinesMatcher.group(1))){
                        while ((line = reader.readLine()) != null){
                            ignoreLinesMatcher = ignoreLinesPattern.matcher(line);
                            if (ignoreLinesMatcher.matches ()) {
                                if ("end".equals(ignoreLinesMatcher.group(1))){
                                    break;
                                }
                            }
                        }
                        continue;
                    }else{
                        try {
                            long ignoreCount = Long.parseLong(ignoreLinesMatcher.group(1));
                            while ( ignoreCount>0 && (line = reader.readLine()) != null){
                                ignoreCount--;
                            }
                            continue;
                        } catch (NumberFormatException | NullPointerException nfe) {
                            throw new ChangeLogParseException("Unknown ignoreLines syntax");
                        }
                    }
                }

                Matcher changeSetPatternMatcher = changeSetPattern.matcher(line);
                if (changeSetPatternMatcher.matches()) {
                    String finalCurrentSql = changeLogParameters.expandExpressions(StringUtil.trimToNull(currentSql.toString()), changeLog);
                    if (changeSet != null) {

                        if (finalCurrentSql == null) {
                            throw new ChangeLogParseException("No SQL for changeset " + changeSet.toString(false));
                        }

                        change.setSql(finalCurrentSql);

                        if (StringUtil.trimToNull(currentRollbackSql.toString()) != null) {
                            if (currentRollbackSql.toString().trim().toLowerCase().matches("^not required.*")) {
                                changeSet.addRollbackChange(new EmptyChange());
                            } else {
                                RawSQLChange rollbackChange = new RawSQLChange();
                                rollbackChange.setSql(changeLogParameters.expandExpressions(currentRollbackSql.toString(), changeLog));
                                if (rollbackSplitStatementsPatternMatcher.matches()) {
                                    rollbackChange.setSplitStatements(rollbackSplitStatements);
                                }
                                if (rollbackEndDelimiter != null) {
                                    rollbackChange.setEndDelimiter(rollbackEndDelimiter);
                                }
                                changeSet.addRollbackChange(rollbackChange);
                            }
                        }
                    }

                    Matcher stripCommentsPatternMatcher = stripCommentsPattern.matcher(line);
                    Matcher splitStatementsPatternMatcher = splitStatementsPattern.matcher(line);
                    Matcher runWithMatcher = runWithPattern.matcher(line);
                    rollbackSplitStatementsPatternMatcher = rollbackSplitStatementsPattern.matcher(line);
                    Matcher endDelimiterPatternMatcher = endDelimiterPattern.matcher(line);
                    Matcher rollbackEndDelimiterPatternMatcher = rollbackEndDelimiterPattern.matcher(line);

                    Matcher logicalFilePathMatcher = logicalFilePathPattern.matcher (line);
                    Matcher runOnChangePatternMatcher = runOnChangePattern.matcher(line);
                    Matcher runAlwaysPatternMatcher = runAlwaysPattern.matcher(line);
                    Matcher contextPatternMatcher = contextPattern.matcher(line);
                    Matcher labelsPatternMatcher = labelsPattern.matcher(line);
                    Matcher runInTransactionPatternMatcher = runInTransactionPattern.matcher(line);
                    Matcher dbmsPatternMatcher = dbmsPattern.matcher(line);
                    Matcher failOnErrorPatternMatcher = failOnErrorPattern.matcher(line);

                    boolean stripComments = parseBoolean(stripCommentsPatternMatcher, changeSet, true);
                    boolean splitStatements = parseBoolean(splitStatementsPatternMatcher, changeSet, true);
                    rollbackSplitStatements = parseBoolean(rollbackSplitStatementsPatternMatcher, changeSet, true);
                    boolean runOnChange = parseBoolean(runOnChangePatternMatcher, changeSet, false);
                    boolean runAlways = parseBoolean(runAlwaysPatternMatcher, changeSet, false);
                    boolean runInTransaction = parseBoolean(runInTransactionPatternMatcher, changeSet, true);
                    boolean failOnError = parseBoolean(failOnErrorPatternMatcher, changeSet, true);

                    String runWith = parseString(runWithMatcher);
                    String endDelimiter = parseString(endDelimiterPatternMatcher);
                    rollbackEndDelimiter = parseString(rollbackEndDelimiterPatternMatcher);
                    String context = StringUtil.trimToNull(
                            StringUtil.trimToEmpty(parseString(contextPatternMatcher)).replaceFirst("^\"", "").replaceFirst("\"$", "") //remove surrounding quotes if they're in there
                    );
                    String labels = parseString(labelsPatternMatcher);
                    String logicalFilePath = parseString(logicalFilePathMatcher);
                    if ((logicalFilePath == null) || "".equals(logicalFilePath)) {
                       logicalFilePath = changeLog.getLogicalFilePath ();
                    }
                    String dbms = parseString(dbmsPatternMatcher);


                    changeSet =
                       new ChangeSet(StringUtil.stripEnclosingQuotes(changeSetPatternMatcher.group(2)), StringUtil.stripEnclosingQuotes(changeSetPatternMatcher.group(1)), runAlways, runOnChange, logicalFilePath, context, dbms, runWith, runInTransaction, changeLog.getObjectQuotingStrategy(), changeLog);
                    changeSet.setLabels(new Labels(labels));
                    changeSet.setFailOnError(failOnError);
                    changeLog.addChangeSet(changeSet);

                    change = new RawSQLChange();
                    change.setSql(finalCurrentSql);
                    if (splitStatementsPatternMatcher.matches()) {
                        change.setSplitStatements(splitStatements);
                    }
                    change.setStripComments(stripComments);
                    change.setEndDelimiter(endDelimiter);
                    changeSet.addChange(change);

                    currentSql.setLength(0);
                    currentRollbackSql.setLength(0);
                } else {
                    if (changeSet != null) {
                        Matcher commentMatcher = commentPattern.matcher(line);
                        Matcher rollbackMatcher = rollbackPattern.matcher(line);
                        Matcher preconditionsMatcher = preconditionsPattern.matcher(line);
                        Matcher preconditionMatcher = preconditionPattern.matcher(line);
                        Matcher validCheckSumMatcher = validCheckSumPattern.matcher(line);

                        if (commentMatcher.matches()) {
                            if (commentMatcher.groupCount() == 1) {
                                changeSet.setComments(commentMatcher.group(1));
                            }
                        } else if (validCheckSumMatcher.matches()) {
                            if (validCheckSumMatcher.groupCount() == 1) {
                                changeSet.addValidCheckSum(validCheckSumMatcher.group(1));
                            }
                        } else if (rollbackMatcher.matches()) {
                            if (rollbackMatcher.groupCount() == 1) {
                                currentRollbackSql.append(rollbackMatcher.group(1)).append(System.lineSeparator());
                            }
                        } else if (preconditionsMatcher.matches()) {
                            if (preconditionsMatcher.groupCount() == 1) {
                                String body = preconditionsMatcher.group(1);
                                Matcher onFailMatcher = onFailPattern.matcher(body);
                                Matcher onErrorMatcher = onErrorPattern.matcher(body);
                                Matcher onUpdateSqlMatcher = onUpdateSqlPattern.matcher(body);

                                PreconditionContainer pc = new PreconditionContainer();
                                pc.setOnFail(StringUtil.trimToNull(parseString(onFailMatcher)));
                                pc.setOnError(StringUtil.trimToNull(parseString(onErrorMatcher)));
                                pc.setOnSqlOutput(StringUtil.trimToNull(parseString(onUpdateSqlMatcher)));
                                changeSet.setPreconditions(pc);
                            }
                        } else if (preconditionMatcher.matches()) {
                            if (changeSet.getPreconditions() == null) {
                                // create the defaults
                                changeSet.setPreconditions(new PreconditionContainer());
                            }
                            if (preconditionMatcher.groupCount() == 2) {
                                String name = StringUtil.trimToNull(preconditionMatcher.group(1));
                                if (name != null) {
                                    String body = preconditionMatcher.group(2).trim();
                                    if ("sql-check".equals(name)) {
                                        changeSet.getPreconditions().addNestedPrecondition(parseSqlCheckCondition(changeLogParameters.expandExpressions(StringUtil.trimToNull(body), changeSet.getChangeLog())));
                                    } else {
                                        throw new ChangeLogParseException("The '" + name + "' precondition type is not supported.");
                                    }
                                }
                            }
                        } else {
                            currentSql.append(line).append(System.lineSeparator());
                        }
                    }
                }
            }

            if (changeSet != null) {
                change.setSql(changeLogParameters.expandExpressions(StringUtil.trimToNull(currentSql.toString()), changeSet.getChangeLog()));

                if ((change.getEndDelimiter() == null) && StringUtil.trimToEmpty(change.getSql()).endsWith("\n/")) {
                    change.setEndDelimiter("\n/$");
                }

                if (StringUtil.trimToNull(currentRollbackSql.toString()) != null) {
                    if (currentRollbackSql.toString().trim().toLowerCase().matches("^not required.*")) {
                        changeSet.addRollbackChange(new EmptyChange());
                    } else {
                        RawSQLChange rollbackChange = new RawSQLChange();
                        rollbackChange.setSql(changeLogParameters.expandExpressions(currentRollbackSql.toString(), changeSet.getChangeLog()));
                        if (rollbackSplitStatementsPatternMatcher.matches()) {
                            rollbackChange.setSplitStatements(rollbackSplitStatements);
                        }
                        if (rollbackEndDelimiter != null) {
                            rollbackChange.setEndDelimiter(rollbackEndDelimiter);
                        }
                        changeSet.addRollbackChange(rollbackChange);
                    }
                }
            }

        } catch (IOException e) {
            throw new ChangeLogParseException(e);
        }

        return changeLog;
    }



    private SqlPrecondition parseSqlCheckCondition(String body) throws ChangeLogParseException{
        Pattern[] patterns = new Pattern[] {
            Pattern.compile("^(?:expectedResult:)?(\\w+) (.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:expectedResult:)?'([^']+)' (.*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:expectedResult:)?\"([^\"]+)\" (.*)", Pattern.CASE_INSENSITIVE)
        };
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(body);
            if (matcher.matches() && (matcher.groupCount() == 2)) {
                SqlPrecondition p = new SqlPrecondition();
                p.setExpectedResult(matcher.group(1));
                p.setSql(matcher.group(2));
                return p;
            }
        }
        throw new ChangeLogParseException("Could not parse a SqlCheck precondition from '" + body + "'.");
    }



    private String parseString(Matcher matcher) {
        String endDelimiter = null;
        if (matcher.matches()) {
            endDelimiter = matcher.group(1);
        }
        return endDelimiter;
    }

    private boolean parseBoolean(Matcher matcher, ChangeSet changeSet, boolean defaultValue) throws ChangeLogParseException {
        boolean stripComments = defaultValue;
        if (matcher.matches()) {
            try {
                stripComments = Boolean.parseBoolean(matcher.group(1));
            } catch (Exception e) {
                throw new ChangeLogParseException("Cannot parse "+changeSet+" "+matcher.toString().replaceAll("\\.*","")+" as a boolean");
            }
        }
        return stripComments;
    }

    protected InputStream openChangeLogFile(String physicalChangeLogLocation, ResourceAccessor resourceAccessor) throws IOException {
        InputStream resourceAsStream = resourceAccessor.openStream(null, physicalChangeLogLocation);
        if (resourceAsStream == null) {
            final File physicalChangeLogFile = new File(physicalChangeLogLocation);
            throw new IOException(FileUtil.getFileNotFoundMessage(physicalChangeLogFile.getAbsolutePath()));
        }
        return resourceAsStream;
    }
}
