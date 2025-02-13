package org.jetbrains.plugins.textmate.language.syntax.lexer;

import com.intellij.util.containers.FList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.textmate.Constants;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.plugins.textmate.language.syntax.SyntaxNodeDescriptor;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateWeigh;
import org.jetbrains.plugins.textmate.regex.MatchData;
import org.jetbrains.plugins.textmate.regex.RegexUtil;
import org.jetbrains.plugins.textmate.regex.StringWithId;
import org.jetbrains.plugins.textmate.regex.TextMateRange;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

public final class TextMateLexer {
  /**
   * Count of {@link #lastSuccessState} that can be occurred again without offset changing.
   * If {@link #lastSuccessStateOccursCount} reaches {@link #MAX_LOOPS_COUNT}
   * then lexing of current line stops and lexer moved to the EOL.
   */
  private static final int MAX_LOOPS_COUNT = 10;

  /**
   * State of the moment when currentOffset had been changed last time.
   * Becomes null on each new line.
   */
  private List<TextMateLexerState> lastSuccessState;

  /**
   * How many times the {@link #lastSuccessState} repeated since last offset changing.
   */
  private int lastSuccessStateOccursCount;

  private int myCurrentOffset = 0;

  private CharSequence myText = "";

  @NotNull
  private TextMateScope myCurrentScope = TextMateScope.EMPTY;
  private FList<TextMateLexerState> myStates = FList.emptyList();

  private final CharSequence myLanguageScopeName;
  private final int myLineLimit;
  private final Runnable myCheckCancelledCallback;
  private final TextMateLexerState myLanguageInitialState;

  public TextMateLexer(@NotNull TextMateLanguageDescriptor languageDescriptor, int lineLimit) {
    myLanguageScopeName = languageDescriptor.getScopeName();
    myLanguageInitialState = TextMateLexerState.notMatched(languageDescriptor.getRootSyntaxNode());
    myLineLimit = lineLimit;
    myCheckCancelledCallback = SyntaxMatchUtils.getCheckCancelledCallback();
  }

  public void init(CharSequence text, int startOffset) {
    myText = text;
    myCurrentOffset = startOffset;

    myStates = FList.<TextMateLexerState>emptyList().prepend(myLanguageInitialState);
    myCurrentScope = new TextMateScope(myLanguageScopeName, null);
    setLastSuccessState(null);
  }

  public int getCurrentOffset() {
    return myCurrentOffset;
  }

  public void advanceLine(@NotNull Queue<Token> output) {
    int startLineOffset = myCurrentOffset;
    int endLineOffset = myCurrentOffset;
    while (endLineOffset < myText.length()) {
      if (myText.charAt(endLineOffset) == '\n') {
        endLineOffset++;
        break;
      }
      endLineOffset++;
    }

    CharSequence lineCharSequence = myText.subSequence(startLineOffset, endLineOffset);
    if (myLineLimit >= 0 && lineCharSequence.length() > myLineLimit) {
      parseLine(lineCharSequence.subSequence(0, myLineLimit), output);
      addToken(output, endLineOffset);
    }
    else {
      parseLine(lineCharSequence, output);
    }
  }

  private void parseLine(@NotNull CharSequence lineCharSequence, @NotNull Queue<Token> output) {
    int startLineOffset = myCurrentOffset;
    int linePosition = 0;
    int lineByteOffset = 0;
    String line = lineCharSequence.length() > 0 && lineCharSequence.charAt(lineCharSequence.length() - 1) == '\n'
                  ? lineCharSequence.toString()
                  : lineCharSequence + "\n";

    StringWithId string = new StringWithId(line);
    while (true) {
      final TextMateLexerState lexerState = myStates.getHead();
      if (lexerState.syntaxRule.getStringAttribute(Constants.StringKey.WHILE) != null
          && !SyntaxMatchUtils.matchStringRegex(Constants.StringKey.WHILE, string, lineByteOffset, lexerState).matched()) {
        closeScopeSelector(output, linePosition + startLineOffset);
        closeScopeSelector(output, linePosition + startLineOffset);
        myStates = myStates.getTail();
      }
      else {
        break;
      }
    }

    final Object2IntMap<List<TextMateLexerState>> localStates = new Object2IntOpenHashMap<>();
    while (true) {
      TextMateLexerState lastState = myStates.getHead();
      SyntaxNodeDescriptor lastRule = lastState.syntaxRule;

      TextMateLexerState currentState =
        SyntaxMatchUtils.matchFirst(lastRule, string, lineByteOffset, TextMateWeigh.Priority.NORMAL, myCurrentScope);
      SyntaxNodeDescriptor currentRule = currentState.syntaxRule;
      MatchData currentMatch = currentState.matchData;

      int endPosition;
      MatchData endMatch = SyntaxMatchUtils.matchStringRegex(Constants.StringKey.END, string, lineByteOffset, lastState);
      if (endMatch.matched() && (!currentMatch.matched() ||
                                 currentMatch.byteOffset().start >= endMatch.byteOffset().start ||
                                 lastState.equals(currentState))) {
        myStates = myStates.getTail();
        TextMateRange endRange = endMatch.charRange(line, string.bytes);
        int startPosition = endPosition = endRange.start;
        closeScopeSelector(output, startPosition + startLineOffset); // closing content scope
        if (lastRule.getCaptures(Constants.CaptureKey.END_CAPTURES) == null
            && lastRule.getCaptures(Constants.CaptureKey.CAPTURES) == null
            && lastRule.getCaptures(Constants.CaptureKey.BEGIN_CAPTURES) == null
            ||
            parseCaptures(output, Constants.CaptureKey.END_CAPTURES, lastRule, endMatch, string, line, startLineOffset)
            ||
            parseCaptures(output, Constants.CaptureKey.CAPTURES, lastRule, endMatch, string, line, startLineOffset)) {
          // move line position only if anything was captured or if there is nothing to capture at all
          endPosition = endRange.end;
        }
        closeScopeSelector(output, endPosition + startLineOffset); // closing basic scope
      }
      else if (currentMatch.matched()) {
        TextMateRange currentRange = currentMatch.charRange(line, string.bytes);
        int startPosition = currentRange.start;
        endPosition = currentRange.end;
        if (currentRule.getStringAttribute(Constants.StringKey.BEGIN) != null) {
          String name = SyntaxMatchUtils.replaceGroupsWithMatchData(currentRule.getStringAttribute(Constants.StringKey.NAME), string, currentMatch, '$');
          openScopeSelector(output, name, startPosition + startLineOffset);

          parseCaptures(output, Constants.CaptureKey.BEGIN_CAPTURES, currentRule, currentMatch, string, line, startLineOffset);
          parseCaptures(output, Constants.CaptureKey.CAPTURES, currentRule, currentMatch, string, line, startLineOffset);

          String contentName = SyntaxMatchUtils.replaceGroupsWithMatchData(currentRule.getStringAttribute(Constants.StringKey.CONTENT_NAME), string, currentMatch, '$');
          openScopeSelector(output, contentName, endPosition + startLineOffset);

          myStates = myStates.prepend(currentState);
        }
        else if (currentRule.getStringAttribute(Constants.StringKey.MATCH) != null) {
          String name = SyntaxMatchUtils.replaceGroupsWithMatchData(currentRule.getStringAttribute(Constants.StringKey.NAME), string, currentMatch, '$');
          openScopeSelector(output, name, startPosition + startLineOffset);
          parseCaptures(output, Constants.CaptureKey.CAPTURES, currentRule, currentMatch, string, line, startLineOffset);
          closeScopeSelector(output, endPosition + startLineOffset);
        }
      }
      else {
        addToken(output, line.length() + startLineOffset);
        break;
      }

      // global looping protection
      List<TextMateLexerState> currentStateSnapshot = myStates;
      if (lastSuccessState != null) {
        if (currentStateSnapshot.equals(lastSuccessState)) {
          lastSuccessStateOccursCount++;
          if (lastSuccessStateOccursCount > MAX_LOOPS_COUNT) {
            addToken(output, line.length() + startLineOffset);
            break;
          }
        }
      }

      // local looping protection
      final int currentStateLocalOccurrencesCount = localStates.getInt(currentStateSnapshot);
      if (currentStateLocalOccurrencesCount <= MAX_LOOPS_COUNT) {
        localStates.put(currentStateSnapshot, currentStateLocalOccurrencesCount + 1);
      }
      else {
        addToken(output, line.length() + startLineOffset);
        break;
      }

      if (linePosition != endPosition) {
        // clear local states history on position changing
        localStates.clear();
        linePosition = endPosition;
        lineByteOffset = RegexUtil.byteOffsetByCharOffset(line, linePosition);
      }

      if (myCheckCancelledCallback != null) {
        myCheckCancelledCallback.run();
      }
    }
  }

  private void setLastSuccessState(@Nullable List<TextMateLexerState> state) {
    lastSuccessState = state;
    lastSuccessStateOccursCount = 0;
  }

  private boolean parseCaptures(@NotNull Queue<Token> output,
                                Constants.CaptureKey capturesKey,
                                SyntaxNodeDescriptor rule,
                                MatchData matchData,
                                StringWithId string,
                                String line,
                                int startLineOffset) {
    Int2ObjectMap<CharSequence> captures = rule.getCaptures(capturesKey);
    if (captures != null) {
      List<CaptureMatchData> matches = SyntaxMatchUtils.matchCaptures(captures, matchData, string, line);
      //noinspection SSBasedInspection
      List<CaptureMatchData> nonEmptyMatches = matches.stream().filter(m -> m.selectorName.length() > 0 && !m.range.isEmpty())
        .collect(Collectors.toList());
      LinkedList<CaptureMatchData> starts = new LinkedList<>(nonEmptyMatches);
      Collections.sort(starts, CaptureMatchData.START_OFFSET_ORDERING);

      LinkedList<CaptureMatchData> ends = new LinkedList<>(nonEmptyMatches);
      Collections.sort(ends, CaptureMatchData.END_OFFSET_ORDERING);

      while (!starts.isEmpty() || !ends.isEmpty()) {
        if (starts.isEmpty()) {
          CaptureMatchData end = ends.removeLast();
          closeScopeSelector(output, end.range.end + startLineOffset);
        }
        else if (ends.isEmpty()) {
          CaptureMatchData start = starts.removeLast();
          String name = SyntaxMatchUtils.replaceGroupsWithMatchData(start.selectorName, string, matchData, '$');
          openScopeSelector(output, name, start.range.start + startLineOffset);
        }
        else if (ends.getLast().group < starts.getLast().group) {
          CaptureMatchData end = ends.removeLast();
          closeScopeSelector(output, end.range.end + startLineOffset);
        }
        else {
          CaptureMatchData start = starts.removeLast();
          String name = SyntaxMatchUtils.replaceGroupsWithMatchData(start.selectorName, string, matchData, '$');
          openScopeSelector(output, name, start.range.start + startLineOffset);
        }
      }
      return !matches.isEmpty();
    }
    return false;
  }


  private void openScopeSelector(@NotNull Queue<Token> output, @Nullable CharSequence name, int position) {
    addToken(output, position);
    myCurrentScope = myCurrentScope.add(name);
  }

  private void closeScopeSelector(@NotNull Queue<Token> output, int position) {
    CharSequence lastOpenedName = myCurrentScope.getScopeName();
    if (lastOpenedName != null && lastOpenedName.length() > 0) {
      addToken(output, position);
    }
    myCurrentScope = myCurrentScope.getParentOrSelf();
  }


  private void addToken(@NotNull Queue<Token> output, int position) {
    if (position > myCurrentOffset) {
      final boolean newState = myCurrentScope.getParent() == null;
      output.offer(new Token(myCurrentScope, myCurrentOffset, position, newState));
      myCurrentOffset = position;
      setLastSuccessState(myStates);
    }
  }

  public static final class Token {
    public final TextMateScope scope;
    public final int startOffset;
    public final int endOffset;
    public final boolean restartable;

    private Token(TextMateScope scope, int startOffset, int endOffset, boolean restartable) {
      this.scope = scope;
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.restartable = restartable;
    }
  }
}
