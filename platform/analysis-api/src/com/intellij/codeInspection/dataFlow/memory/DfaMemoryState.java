// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow.memory;

import com.intellij.codeInspection.dataFlow.types.DfType;
import com.intellij.codeInspection.dataFlow.value.DfaCondition;
import com.intellij.codeInspection.dataFlow.value.DfaValue;
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue;
import com.intellij.codeInspection.dataFlow.value.RelationType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Represents a memory state of abstract interpreter.
 * It's mutable and not thread-safe!
 */
public interface DfaMemoryState {
  /**
   * @return a copy of this memory state
   */
  @NotNull
  DfaMemoryState createCopy();

  /**
   * @return a copy of this memory state that has empty stack and flushed unstable values. It could be used
   * to initialize a closure that is declared at a given code location.
   */
  @NotNull
  DfaMemoryState createClosureState();

  /**
   * Pops single value from the top of the stack and returns it
   * @return popped value
   * @throws java.util.EmptyStackException if stack is empty
   */
  @NotNull DfaValue pop();

  /**
   * Reads a value from the top of the stack without popping it
   * @return top of stack value
   * @throws java.util.EmptyStackException if stack is empty
   */
  @NotNull DfaValue peek();

  /**
   * Reads a value from the stack at given offset from the top without popping it
   * @param offset value from the stack (0 = top of stack, 1 = the next one, etc.)
   * @return stack value; null if the stack is not deep enough
   * @throws IndexOutOfBoundsException if offset is negative
   */
  @Nullable DfaValue getStackValue(int offset);

  /**
   * @return true if there are no values in the stack
   */
  boolean isEmptyStack();

  /**
   * Pushes given value to the stack
   * @param value to push
   */
  void push(@NotNull DfaValue value);

  /**
   * Clears the stack completely or until the next control transfer value
   */
  void emptyStack();

  /**
   * Sets the value for a given variable
   *
   * @param var variable to update
   * @param value variable value
   */
  void setVarValue(@NotNull DfaVariableValue var, @NotNull DfaValue value);

  /**
   * Returns a relation between given values within this state, if known
   * @param left first value
   * @param right second value
   * @return a relation (EQ, NE, GT, LT), or null if not known.
   */
  @Nullable RelationType getRelation(@NotNull DfaValue left, @NotNull DfaValue right);

  /**
   * Applies condition to this state.
   *
   * @param dfaCond condition to apply.
   * @return true if condition is successfully applied (the state could be narrowed);
   * false if the condition cannot be satisfied for this state. If false is returned, then this state
   * should not be used anymore, as it could be inconsistent.
   */
  boolean applyCondition(@NotNull DfaCondition dfaCond);

  /**
   * Returns true if given two values are known to be equal
   *
   * @param value1 first value to check
   * @param value2 second value to check
   * @return true if they are equal; false if not equal or not known
   */
  boolean areEqual(@NotNull DfaValue value1, @NotNull DfaValue value2);

  /**
   * Applies contract condition to this state. This may make more states ephemeral, depending on contract rules.
   *
   * @param dfaCond condition to apply.
   * @return true if condition is successfully applied (the state could be narrowed);
   * false if the condition cannot be satisfied for this state. If false is returned, then this state
   * should not be used anymore, as it could be inconsistent.
   */
  boolean applyContractCondition(@NotNull DfaCondition dfaCond);

  /**
   * Updates value dfType if it's compatible with current value state.
   * Depending on value passed and memory state implementation the new fact may or may not be memoized.
   *
   * @param value value to update
   * @param dfType wanted type
   * @return true if update was successful. If false was returned the memory state may be in inconsistent state.
   */
  boolean meetDfType(@NotNull DfaValue value, @NotNull DfType dfType);

  /**
   * Forcibly sets the supplied dfType to given value if given value state can be memoized.
   * This is necessary to override some knowledge about the variable state. In most of the cases
   * {@link #meetDfType(DfaValue, DfType)} should be used as it narrows existing type.
   *
   * @param value value to update.
   * @param dfType type to assign to value. Note that type might be adjusted, e.g. to be compatible with value declared PsiType.
   */
  void setDfType(@NotNull DfaValue value, @NotNull DfType dfType);

  /**
   * @param value value to get the type of
   * @return the DfType of the value within this memory state
   */
  @NotNull DfType getDfType(@NotNull DfaValue value);

  /**
   * @param value value to get the type of; if value is a primitive wrapper, it will be unboxed before fetching the DfType
   * @return the DfType of the value within this memory state
   */
  @NotNull DfType getUnboxedDfType(@NotNull DfaValue value);

  /**
   * Forget values of all unstable fields that could be
   * qualified by one of specified qualifiers (including possible aliases).
   */
  void flushFieldsQualifiedBy(@NotNull Set<DfaValue> qualifiers);

  /**
   * Forget values of all unstable fields.
   */
  void flushFields();

  /**
   * Flush given variable (forget any knowledge about it). Equivalent to {@code flushVariable(variable, true)}
   * @param variable to flush
   */
  void flushVariable(@NotNull DfaVariableValue variable);

  /**
   * Flush given variable (forget any knowledge about it)
   * @param variable to flush
   * @param canonicalize whether to canonicalize the variable before flushing. Flushing canonical variable allows to forget
   *                     about all known aliases as well. Flushing without canonicalization could be necessary only
   *                     to simplify memory state, if it's known that given variable is never used anymore.
   */
  void flushVariable(@NotNull DfaVariableValue variable, boolean canonicalize);

  /**
   * Mark this state as ephemeral. See {@link #isEphemeral()} for details.
   */
  void markEphemeral();

  /**
   * Ephemeral means a state that could be unreachable under normal program execution. Examples of ephemeral states include:
   * <ul>
   * <li>State created by method contract processing that checks for null (otherwise, if argument has unknown nullity, this would make it nullable)</li>
   * <li>State that appears on VM exception path (e.g. catching NPE or CCE)</li>
   * <li>State that appears when {@linkplain com.intellij.codeInspection.dataFlow.types.DfEphemeralType an ephemeral value} is stored on the stack</li>
   * </ul>
   * The "unsound" warnings (e.g. possible NPE) are not reported if they happen only in ephemeral states, and there's a non-ephemeral state
   * where the same problem doesn't happen.
   */
  boolean isEphemeral();

  /**
   * Returns true if two given values should be compared by content, rather than by reference.
   * @param dfaLeft left value
   * @param dfaRight right value
   * @return true if two given values should be compared by content, rather than by reference.
   */
  boolean shouldCompareByEquals(DfaValue dfaLeft, DfaValue dfaRight);

  /**
   * Widen this memory state on back-branches
   */
  void widen();
}
