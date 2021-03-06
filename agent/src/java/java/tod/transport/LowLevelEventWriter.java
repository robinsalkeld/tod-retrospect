/*
TOD - Trace Oriented Debugger.
Copyright (c) 2006-2008, Guillaume Pothier
All rights reserved.

This program is free software; you can redistribute it and/or 
modify it under the terms of the GNU General Public License 
version 2 as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, 
but WITHOUT ANY WARRANTY; without even the implied warranty of 
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
General Public License for more details.

You should have received a copy of the GNU General Public License 
along with this program; if not, write to the Free Software 
Foundation, Inc., 59 Temple Place, Suite 330, Boston, 
MA 02111-1307 USA

Parts of this work rely on the MD5 algorithm "derived from the 
RSA Data Security, Inc. MD5 Message-Digest Algorithm".
*/
package java.tod.transport;

import static java.tod._LowLevelEventType.AFTER_CALL;
import static java.tod._LowLevelEventType.AFTER_CALL_DRY;
import static java.tod._LowLevelEventType.AFTER_CALL_EXCEPTION;
import static java.tod._LowLevelEventType.ARRAY_WRITE;
import static java.tod._LowLevelEventType.BEFORE_CALL;
import static java.tod._LowLevelEventType.BEFORE_CALL_DRY;
import static java.tod._LowLevelEventType.BEHAVIOR_ENTER;
import static java.tod._LowLevelEventType.BEHAVIOR_EXIT;
import static java.tod._LowLevelEventType.BEHAVIOR_EXIT_EXCEPTION;
import static java.tod._LowLevelEventType.CLINIT_ENTER;
import static java.tod._LowLevelEventType.CLINIT_EXIT;
import static java.tod._LowLevelEventType.EXCEPTION_GENERATED;
import static java.tod._LowLevelEventType.FIELD_WRITE;
import static java.tod._LowLevelEventType.INSTANCEOF;
import static java.tod._LowLevelEventType.LOCAL_VARIABLE_WRITE;
import static java.tod._LowLevelEventType.NEW_ARRAY;
import static java.tod._LowLevelEventType.REGISTER_OBJECT;
import static java.tod._LowLevelEventType.REGISTER_THREAD;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.tod.EventCollector;
import java.tod.ExceptionGeneratedReceiver;
import java.tod.ObjectIdentity;
import java.tod._BehaviorCallType;
import java.tod._LowLevelEventType;
import java.tod._Output;
import java.tod._ValueType;
import java.util.IdentityHashMap;

import tod.agent.Command;
import tod.agent.ObjectValue;
import tod.agent.ObjectValue.FieldValue;
import tod.agent.io._ByteBuffer;
import tod.agent.io._GrowingByteBuffer;
import tod.agent.util._ArrayList;

/**
 * Provides the methods used to encode streamed log data. Non-static methods are
 * not thread-safe, but {@link EventCollector} maintains one
 * {@link LowLevelEventWriter} per thread.
 */
public class LowLevelEventWriter
{
	private static final byte TRUE = (byte) 1;
	private static final byte FALSE = (byte) 0;
	
	/**
	 * The buffer to which packets must be sent. 
	 */
	private final PacketBuffer itsStream;
	
	/**
	 * Backing array for {@link #itsBuffer}
	 */
	private final byte[] itsBufferStore;
	
	/**
	 * Buffer to prepare (small) packets
	 */
	private final _ByteBuffer itsBuffer;
	
	private RegisteredObjectsQueue itsRegisteredObjectsQueue = new RegisteredObjectsQueue();
	private DeferredObjectsStack itsDeferredObjectsStack = new DeferredObjectsStack();
	private RegisteredRefObjectsQueue itsRegisteredRefObjectsQueue = new RegisteredRefObjectsQueue();
	
	private _GrowingByteBuffer itsObjectsBuffer = _GrowingByteBuffer.allocate(1024);
	
	/**
	 * Set of objects we have seen the instantiation event for.
	 */
	private static final IdentityHashMap<Object, Void> instantiatedObjects = new IdentityHashMap<Object, Void>();
	
	public LowLevelEventWriter(PacketBuffer aStream)
	{
		itsStream = aStream;
		itsBufferStore = new byte[4096];
		itsBuffer = _ByteBuffer.wrap(itsBufferStore);
	}

	private static void sendEventType(_ByteBuffer aBuffer, _LowLevelEventType aType) 
	{
		aBuffer.put((byte) aType.ordinal());
	}

	private static void sendValueType(_ByteBuffer aBuffer, _ValueType aType) 
	{
		aBuffer.put((byte) aType.ordinal());
	}
	
	private static void sendCommand(_ByteBuffer aBuffer, Command aCommands) 
	{
		aBuffer.put((byte) (aCommands.ordinal() + Command.BASE));
	}
	
	private static void sendCallType(_ByteBuffer aBuffer, _BehaviorCallType aType)
	{
		aBuffer.put((byte) aType.ordinal());
	}
	
	private static void sendString(_ByteBuffer aBuffer, String aString)
	{
		int theSize = aString.length();
		aBuffer.putInt(theSize);
		for(int i=0;i<theSize;i++) aBuffer.putChar(aString.charAt(i));
	}
	
	private void sendStd(
			_LowLevelEventType aType,
			long aTimestamp)
	{
//		_IO.out("Starting packet: "+itsStream.getThreadId()+" - "+aTimestamp);
		sendEventType(itsBuffer, aType);
		itsBuffer.putLong(aTimestamp);
	}

	/**
	 * Copy the temporary buffer to the output stream, and resets the buffer.
	 */
	private void copyBuffer() 
	{
		itsStream.write(itsBufferStore, itsBuffer.position(), false);
		itsBuffer.clear();
	}
	
	public void sendClInitEnter(
			long aTimestamp,
			int aBehaviorId, 
			_BehaviorCallType aCallType) 
	{
		sendStd(CLINIT_ENTER, aTimestamp);

		itsBuffer.putInt(aBehaviorId);
		sendCallType(itsBuffer, aCallType);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendBehaviorEnter(
			long aTimestamp,
			int aBehaviorId, 
			_BehaviorCallType aCallType,
			Object aTarget, 
			Object[] aArguments) 
	{
		sendStd(BEHAVIOR_ENTER, aTimestamp);

		itsBuffer.putInt(aBehaviorId);
		sendCallType(itsBuffer, aCallType);
		sendValue(itsBuffer, aTarget, aTimestamp);
		sendArguments(itsBuffer, aArguments, aTimestamp);

		copyBuffer();

		sendRegisteredObjects();
	}

	public void sendClInitExit(
			long aTimestamp,
			int aProbeId, 
			int aBehaviorId)
	{
		sendStd(CLINIT_EXIT, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aBehaviorId);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendBehaviorExit(
			long aTimestamp,
			int aProbeId, 
			int aBehaviorId,
			Object aResult) 
	{
		sendStd(BEHAVIOR_EXIT, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aBehaviorId);
		sendValue(itsBuffer, aResult, aTimestamp);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendBehaviorExitWithException(
			long aTimestamp,
			int aBehaviorId, 
			Object aException) 
	{
		sendStd(BEHAVIOR_EXIT_EXCEPTION, aTimestamp);

		itsBuffer.putInt(aBehaviorId);
		sendValue(itsBuffer, aException, aTimestamp);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendExceptionGenerated(
			long aTimestamp,
			String aMethodName,
			String aMethodSignature,
			String aMethodDeclaringClassSignature, 
			int aOperationBytecodeIndex,
			Object aException) 
	{
		sendStd(EXCEPTION_GENERATED, aTimestamp);

		sendString(itsBuffer, aMethodName);
		sendString(itsBuffer, aMethodSignature);
		sendString(itsBuffer, aMethodDeclaringClassSignature);
		itsBuffer.putShort((short) aOperationBytecodeIndex);
		sendValue(itsBuffer, aException, aTimestamp);

		copyBuffer();

		// There might be harmless recursive exceptions here, ignore
		// Note: this is only for exception events, other events don't need this.
		ExceptionGeneratedReceiver.setIgnoreExceptions(true);
		sendRegisteredObjects();
		ExceptionGeneratedReceiver.setIgnoreExceptions(false);
	}
	
	public void sendFieldWrite(
			long aTimestamp,
			int aProbeId, 
			int aFieldId,
			Object aTarget, 
			Object aValue) 
	{
		sendStd(FIELD_WRITE, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aFieldId);
		sendValue(itsBuffer, aTarget, aTimestamp);
		sendValue(itsBuffer, aValue, aTimestamp);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendNewArray(
			long aTimestamp,
			int aProbeId, 
			Object aTarget,
			int aBaseTypeId,
			int aSize) 
	{
		sendStd(NEW_ARRAY, aTimestamp);

		itsBuffer.putInt(aProbeId);
		sendValue(itsBuffer, aTarget, aTimestamp);
		itsBuffer.putInt(aBaseTypeId);
		itsBuffer.putInt(aSize);

		copyBuffer();

		sendRegisteredObjects();
	}
	

	public void sendArrayWrite(
			long aTimestamp,
			int aProbeId, 
			Object aTarget,
			int aIndex,
			Object aValue) 
	{
		sendStd(ARRAY_WRITE, aTimestamp);

		itsBuffer.putInt(aProbeId);
		sendValue(itsBuffer, aTarget, aTimestamp);
		itsBuffer.putInt(aIndex);
		sendValue(itsBuffer, aValue, aTimestamp);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendInstanceOf(
			long aTimestamp,
			int aProbeId, 
			Object aObject,
			int aTypeId,
			boolean aResult) 
	{
		sendStd(INSTANCEOF, aTimestamp);
		
		itsBuffer.putInt(aProbeId);
		sendValue(itsBuffer, aObject, aTimestamp);
		itsBuffer.putInt(aTypeId);
		itsBuffer.put(aResult ? TRUE : FALSE);
		
		copyBuffer();
		
		sendRegisteredObjects();
	}
	
	public void sendLocalVariableWrite(
			long aTimestamp,
			int aProbeId, 
			int aVariableId,
			Object aValue) 
	{
		sendStd(LOCAL_VARIABLE_WRITE, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aVariableId);
		sendValue(itsBuffer, aValue, aTimestamp);

		copyBuffer();

		sendRegisteredObjects();
	}
	
	public void sendBeforeBehaviorCallDry(
			long aTimestamp,
			int aProbeId, 
			int aBehaviorId,
			_BehaviorCallType aCallType) 
	{
		sendStd(BEFORE_CALL_DRY, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aBehaviorId);
		sendCallType(itsBuffer, aCallType);

		copyBuffer();
	}
	
	public void sendAfterBehaviorCallDry(
			long aTimestamp) 
	{
		sendStd(AFTER_CALL_DRY, aTimestamp);

		copyBuffer();
	}
	


	/**
	 * Sends the target object of a call event.
	 * Ensures that the sending of the object's value is deferred in 
	 * the case of instantiations:
	 * otherwise we serialize an object that is not completely
	 * initialized (eg. new String(byteArray, 5, 10)).
	 */
	private void sendTarget(
			long aTimestamp,
			int aDeferRequestorId,
			_BehaviorCallType aCallType, 
			Object aTarget) 
	{
		if (aCallType == _BehaviorCallType.INSTANTIATION && shouldSendByValue(aTarget))
		{
			// Ensure that the sending of the object's value is deferred:
			// otherwise we serialize an object that is not completely
			// initialized (eg. new String(byteArray, 5, 10)).
			sendValue(itsBuffer, aTarget, aTimestamp, aDeferRequestorId);
		}
		else
		{
			sendValue(itsBuffer, aTarget, aTimestamp);
		}
	}

	/**
	 * For exit/after events, check if there is a deferred entry corresponding to
	 * the given parent timestamp.
	 */
	private void checkDeferred(int aDeferRequestorId) 
	{
		if (itsDeferredObjectsStack.isAvailable(aDeferRequestorId))
		{
			DeferredObjectEntry theEntry = itsDeferredObjectsStack.pop();
			// _IO.out("Sending deferred object: "+theEntry.id);
			sendRegisteredObject(theEntry.id, theEntry.object, theEntry.timestamp);
		}
	}

	
	public void sendBeforeBehaviorCall(
			long aTimestamp,
			int aProbeId, 
			int aBehaviorId,
			_BehaviorCallType aCallType,
			Object aTarget, 
			Object[] aArguments) 
	{
		sendStd(BEFORE_CALL, aTimestamp);

		if (aCallType == _BehaviorCallType.INSTANTIATION) {
		    markInstantiated(aTarget);
		}
		
		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aBehaviorId);
		sendCallType(itsBuffer, aCallType);
		sendTarget(aTimestamp, aBehaviorId, aCallType, aTarget);
		sendArguments(itsBuffer, aArguments, aTimestamp);
		
		copyBuffer();
		
		sendRegisteredObjects();
	}

	public void sendAfterBehaviorCall(
			long aTimestamp,
			int aProbeId, 
			int aBehaviorId, 
			Object aTarget,
			Object aResult) 
	{
		sendStd(AFTER_CALL, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aBehaviorId);
		sendValue(itsBuffer, aTarget, aTimestamp);
		sendValue(itsBuffer, aResult, aTimestamp);

		copyBuffer();
		
		checkDeferred(aBehaviorId);
		sendRegisteredObjects();
	}
	
	public void sendAfterBehaviorCallWithException(
			long aTimestamp,
			int aProbeId, 
			int aBehaviorId, 
			Object aTarget, 
			Object aException) 
	{
		sendStd(AFTER_CALL_EXCEPTION, aTimestamp);

		itsBuffer.putInt(aProbeId);
		itsBuffer.putInt(aBehaviorId);
		sendValue(itsBuffer, aTarget, aTimestamp);
		sendValue(itsBuffer, aException, aTimestamp);

		copyBuffer();
		
		checkDeferred(aBehaviorId);
		sendRegisteredObjects();

	}
	
	public void sendOutput(
			long aTimestamp,
			_Output aOutput, 
			byte[] aData) 
	{
		throw new UnsupportedOperationException();
	}

	public void sendObject(long aTimestamp, Object anObject)
	{
	        if (shouldSkipObject(anObject))
	        {
	            return;
	        }
	        
	        // Called for the side effect of registering the object if it is new.
	        getObjectId(anObject, aTimestamp);
	        
	        sendRegisteredObjects();
	}
	
	/**
	 * Determines if the given object should be sent by value.
	 */
	public static boolean shouldSendByValue(Object aObject)
	{
		return (aObject instanceof String) || (aObject instanceof Throwable) || !instantiated(aObject);
	}

	/**
	 * Determines if the given object is indexable (see ILogCollector.register)
	 */
	public static boolean isIndexable(Object aObject)
	{
		return (aObject instanceof String);
	}
	
	public static synchronized void markInstantiated(Object aObject) {
	    instantiatedObjects.put(aObject, null);
	}
	
	public static synchronized boolean instantiated(Object aObject) {
	    return instantiatedObjects.containsKey(aObject);
	}
	
	public void sendThread(
	                long aTimestamp,
			long aJVMThreadId,
			Thread thread,
			String aName) 
	{
		sendEventType(itsBuffer, REGISTER_THREAD);

		itsBuffer.putLong(aJVMThreadId);
		sendValue(itsBuffer, thread, aTimestamp);
		sendString(itsBuffer, aName);

		copyBuffer();

		sendRegisteredObjects();
	}

	public void sendClear() 
	{
		sendCommand(itsBuffer, Command.DBCMD_CLEAR);
		copyBuffer();
	}

	public void sendFlush() 
	{
		sendCommand(itsBuffer, Command.DBCMD_FLUSH);
		copyBuffer();
	}
	
	public void sendEnd() 
	{
		sendCommand(itsBuffer, Command.DBCMD_END);
		copyBuffer();
	}
	
	public void sendEvCaptureEnabled(boolean aValue)
	{
		sendCommand(itsBuffer, Command.DBEV_CAPTURE_ENABLED);
		itsBuffer.put(aValue ? (byte) 1 : (byte) 0);
		copyBuffer();
	}
	
	/**
	 * Sends an argument to the socket. This method handles arrays, single
	 * objects or null values.
	 */
	private void sendArguments(
			_ByteBuffer aBuffer, 
			Object[] aArguments, 
			long aTimestamp) 
	{
		aBuffer.putInt(aArguments != null ? aArguments.length : 0);

		if (aArguments != null) for (Object theArgument : aArguments)
			sendValue(aBuffer, theArgument, aTimestamp);
	}

	private void sendValue(_ByteBuffer aBuffer, Object aValue, long aTimestamp) 
	{
		sendValue(aBuffer, aValue, aTimestamp, -1);
	}

	private void sendValue(_ByteBuffer aBuffer, Object aValue, long aTimestamp, int aDeferRequestor) 
	{
		if (aValue == null)
		{
			sendValueType(aBuffer, _ValueType.NULL);
		}
		else if (aValue instanceof Boolean)
		{
			Boolean theBoolean = (Boolean) aValue;
			sendValueType(aBuffer, _ValueType.BOOLEAN);
			aBuffer.put(theBoolean.booleanValue() ? TRUE : FALSE);
		}
		else if (aValue instanceof Byte)
		{
			Byte theByte = (Byte) aValue;
			sendValueType(aBuffer, _ValueType.BYTE);
			aBuffer.put(theByte.byteValue());
		}
		else if (aValue instanceof Character)
		{
			Character theCharacter = (Character) aValue;
			sendValueType(aBuffer, _ValueType.CHAR);
			aBuffer.putChar(theCharacter.charValue());
		}
		else if (aValue instanceof Integer)
		{
			Integer theInteger = (Integer) aValue;
			sendValueType(aBuffer, _ValueType.INT);
			aBuffer.putInt(theInteger.intValue());
		}
		else if (aValue instanceof Long)
		{
			Long theLong = (Long) aValue;
			sendValueType(aBuffer, _ValueType.LONG);
			aBuffer.putLong(theLong.longValue());
		}
		else if (aValue instanceof Float)
		{
			Float theFloat = (Float) aValue;
			sendValueType(aBuffer, _ValueType.FLOAT);
			aBuffer.putFloat(theFloat.floatValue());
		}
		else if (aValue instanceof Double)
		{
			Double theDouble = (Double) aValue;
			sendValueType(aBuffer, _ValueType.DOUBLE);
			aBuffer.putDouble(theDouble.doubleValue());
		}
		else if (shouldSendByValue(aValue))
		{
			sendObjectByValue(aBuffer, aValue, aTimestamp, aDeferRequestor);
		}
		else
		{
			sendObjectByRef(aBuffer, aValue, aTimestamp);
		}
	}

	/**
	 * Sends an object by value. This method checks if the object already had an
	 * id. If it didn't, it is placed on the registered objects stack so that
	 * its value is sent when {@link #sendRegisteredObjects()} is called. In any
	 * case, the id of the object is sent.
	 * 
	 * @param aDeferRequestor
	 *            If positive, and if the object didn't have an id, the object
	 *            is placed on a defered objects stack instead of the registered
	 *            objects stack. The value of this parameter is the behavior id of
	 *            the event that "request" the deferring.
	 */
	private void sendObjectByValue(_ByteBuffer aBuffer, Object aObject, long aTimestamp, int aDeferRequestor) 
	{
		long theObjectId = getObjectId(aObject, aTimestamp, aDeferRequestor);
		sendValueType(aBuffer, _ValueType.OBJECT_UID);
		aBuffer.putLong(theObjectId);
	}

	/**
	 * Sends an object by reference. This method checks if the object already had an
	 * id. If it didn't, it is placed on the registered refs stack so that
	 * its type is sent when {@link #sendRegisteredObjects()} is called. In any
	 * case, the id of the object is sent.
	 */
	private void sendObjectByRef(_ByteBuffer aBuffer, Object aObject, long aTimestamp) 
	{
		long theObjectId = ObjectIdentity.get(aObject);
                assert theObjectId != 0;
                
                if (theObjectId < 0)
                {
                        // First time this object appears, register its type
                        theObjectId = -theObjectId;
                        Class<?> theClass = aObject.getClass();
                        itsRegisteredRefObjectsQueue.push(aObject, theObjectId, theClass, aTimestamp);
                }

                sendValueType(aBuffer, _ValueType.OBJECT_UID);
                aBuffer.putLong(theObjectId);
	}
	
	/**
	 * Sends all pending registered objects.
	 */
	private void sendRegisteredObjects() 
	{
		// Note: remember that this is thread-safe because SocketCollector has one
		// CollectorPacketWriter per thread.

    		while (!itsRegisteredObjectsQueue.isEmpty())
    		{
    			ObjectEntry theEntry = itsRegisteredObjectsQueue.pop();
    			sendRegisteredObject(theEntry.id, theEntry.getValue(), theEntry.timestamp);
    		}
    		
    		while (!itsRegisteredRefObjectsQueue.isEmpty())
    		{
    			RefObjectEntry theEntry = itsRegisteredRefObjectsQueue.pop();
    			sendRegisteredRefObject(theEntry.getValue(), theEntry.id, theEntry.cls, theEntry.timestamp);
    		}
	}

	public static boolean shouldSkipObject(Object anObject)
	{
	    if (anObject == null) 
	    {
	        return false;
	    }
	    
	    Class<?> klass = anObject instanceof Class<?> ? (Class<?>)anObject : anObject.getClass();
	    String className = klass.getName();
            if (className.startsWith("java.tod") || className.startsWith("tod.agent")) 
            {
                return true;
            }
            else
            {
                return false;
            }
	}
	
	private void sendRegisteredObject(long aId, Object aObject, long aTimestamp) 
	{
	    if (aObject instanceof Class<?>) {
	        sendRegisterClass(aId, (Class<?>) aObject, true, aTimestamp);
	    }

	    if (aObject instanceof ClassLoader) {
	        sendRegisterClassLoader(aId, (ClassLoader) aObject, aTimestamp);
	    }
            
		// We use a different buffer here because the packet might be huge.
		itsObjectsBuffer.clear();
		itsObjectsBuffer.position(22); // Header placeholder
		
		Object converted = convertObject(aObject, aObject.getClass(), aTimestamp);
		if (aObject instanceof Class<?>) {
		    Object staticFields = convertObject(null, (Class<?>)aObject, aTimestamp);
		    converted = new Object[] { converted, staticFields };
		}
		
		ObjectEncoder.encode(converted, itsObjectsBuffer);
		
		int theSize = itsObjectsBuffer.position()-5; // 5: event type + size 
	
		itsObjectsBuffer.position(0);
		sendEventType(itsObjectsBuffer, REGISTER_OBJECT);
		itsObjectsBuffer.putInt(theSize); 
		
		itsObjectsBuffer.putLong(aId);
		itsObjectsBuffer.putLong(aTimestamp);
		itsObjectsBuffer.put(isIndexable(aObject) ? (byte) 1 : (byte) 0);
		
		itsStream.write(itsObjectsBuffer.array(), theSize+5, true);
	}
	
	private Object convertObjectByRef(Class<?> aDeclaredType, Object aValue, long aTimestamp) {
	    if (shouldSkipObject(aValue)) 
	    {
	        return null;
	    }
	    else if (aValue != null && !aDeclaredType.isPrimitive()) 
	    {
                return new _ObjectId(getObjectId(aValue, aTimestamp));
            } 
	    else 
	    {
                return aValue;
            }
	}
	
	private Object convertObject(Object aObject, Class<?> aClass, long aTimestamp) {
	    if (aObject != null && aObject.getClass().isArray()) {
	        Class<?> theElementType = aObject.getClass().getComponentType();
                Object[] values = new Object[Array.getLength(aObject)];
	        for (int i = 0; i < values.length; i++) {
                    values[i] = convertObjectByRef(theElementType, Array.get(aObject, i), aTimestamp);
                }
	        return values;
	        
	    } else {
    	        ObjectValue theObjectValue = new ObjectValue(aClass.getName(), aObject instanceof Throwable);
                _ArrayList<FieldValue> theFieldValues = new _ArrayList<FieldValue>();
                boolean staticFields = aObject == null;
                
                while (aClass != null)
                {
                        Field[] theFields = aClass.getDeclaredFields();
                        for (Field theField : theFields)
                        {
                                if (Modifier.isStatic(theField.getModifiers()) != staticFields) {
                                    continue;
                                }
                            
                                boolean theWasAccessible = theField.isAccessible();
                                theField.setAccessible(true);
    
                                Object theValue;
                                try
                                {
                                        theValue = theField.get(aObject);
                                }
                                catch (Exception e)
                                {
                                        theValue = "Cannot obtain field value: "+e.getMessage();
                                }
                                
                                theField.setAccessible(theWasAccessible);
                                
                                theFieldValues.add(new FieldValue(theField.getName(), convertObjectByRef(theField.getType(), theValue, aTimestamp)));
                        }
                        
                        aClass = aClass.getSuperclass();
                }
                
                theObjectValue.setFields(theFieldValues.toArray(new FieldValue[theFieldValues.size()]));
                return theObjectValue;
	    }
	}
	
	private void sendRegisteredRefObject(Object aObject, long aId, Class<?> aClass, long aTimestamp) 
	{
		long theClassId = getObjectId(aClass, aTimestamp); 
                
		sendEventType(itsBuffer, _LowLevelEventType.REGISTER_REFOBJECT);
		
		itsBuffer.putLong(aId);
		itsBuffer.putLong(aTimestamp);
		itsBuffer.putLong(theClassId);
		
		copyBuffer();
	}
	
	private long getObjectId(Object aObject, long aTimestamp) 
        {
	        return getObjectId(aObject, aTimestamp, -1);
        }
	
	private long getObjectId(Object aObject, long aTimestamp, int aDeferRequestor) 
	{
	        if (aObject == null) {
	            return 0;
	        }
	    
	        if (shouldSkipObject(aObject)) 
	        {
	            shouldSkipObject(aObject);
	        }
	        
		long theObjectId = ObjectIdentity.get(aObject);
		assert theObjectId != 0;
		
		if (theObjectId < 0)
		{
			// First time this object appears, register it
                        theObjectId = -theObjectId;
                        if (aDeferRequestor == -1)
                        {
                            itsRegisteredObjectsQueue.push(theObjectId, aObject, aTimestamp);
                        
                        }
                        else
                        {
                            itsDeferredObjectsStack.push(aDeferRequestor, theObjectId, aObject, aTimestamp);
                        }
                        
                        // Also register a reference so that we know its type
                        itsRegisteredRefObjectsQueue.push(aObject, theObjectId, aObject.getClass(), aTimestamp);
                        
                        // Also make sure the object's type/class loader id is generated so that the reference is valid.
                        getObjectId(aObject.getClass(), aTimestamp, aDeferRequestor);
                        if (aObject instanceof Class<?>) {
                            getObjectId(((Class<?>)aObject).getClassLoader(), aTimestamp, aDeferRequestor);
                        }
		}
		
		return theObjectId;
	}
	
	
	private void sendRegisterClass(long aClassId, Class<?> aClass, boolean initialized, long aTimestamp) 
	{
		// That must stay before we start using the buffer
		long theLoaderId = getObjectId(aClass.getClassLoader(), aTimestamp);
		
		sendEventType(itsBuffer, _LowLevelEventType.REGISTER_CLASS);
		
		itsBuffer.putLong(aClassId);
		itsBuffer.putLong(theLoaderId);
		itsBuffer.put(initialized ? TRUE : FALSE);
                
		String theName = aClass.getName();
		itsBuffer.putShort((short) theName.length());
		for(int i=0;i<theName.length();i++) itsBuffer.putChar(theName.charAt(i));
		
		copyBuffer();
	}
	
	private void sendRegisterClassLoader(long aLoaderId, ClassLoader aLoader, long aTimestamp) 
	{
		long theLoaderClassId = getObjectId(aLoader.getClass(), aTimestamp);
		
		sendEventType(itsBuffer, _LowLevelEventType.REGISTER_CLASSLOADER);
		
		itsBuffer.putLong(aLoaderId);
		itsBuffer.putLong(theLoaderClassId);
		
		copyBuffer();
	}
	
	public void sendClassLoaded(Class<?> aClass, long aTimestamp) {
	        if (shouldSkipObject(aClass))
                {
                    return;
                }
                
                // Called just for the side effect of registering the class state
	        // if this is the first time we've seen it.
	        getObjectId(aClass, aTimestamp);
	}
	
	/**
	 * A queue of objects pending to be sent. This is a queue since the objects need
	 * to arrive in order by id.
	 * 
	 * @author gpothier
	 */
	private static class RegisteredObjectsQueue
	{
		/**
		 * List of registered objects that must be sent.
		 */
		private final NakedLinkedList<Object> itsObjects = new NakedLinkedList<Object>();

		public void push(long aId, Object aObject, long aTimestamp)
		{
		        if (shouldSkipObject(aObject)) {
		            shouldSkipObject(aObject);
		        }
			itsObjects.addFirst(new ObjectEntry(aId, aObject, aTimestamp));
		}

		public boolean isEmpty()
		{
			return itsObjects.size() == 0;
		}

		public ObjectEntry pop()
		{
		        ObjectEntry result = (ObjectEntry)itsObjects.getLastEntry();
			itsObjects.remove(result);
			return result;
		}
	}

	private static class ObjectEntry extends NakedLinkedList.Entry<Object>
	{
		public long id;
		public long timestamp;

		public ObjectEntry(long aId, Object aObject, long aTimestamp)
		{
		        super(aObject);
			id = aId;
			timestamp = aTimestamp;
		}
	}

	/**
	 * A stack of objects whose registration is deferred. When we detect the
	 * instantiation of an object that is sent by value,
	 * 
	 * @author gpothier
	 */
	private static class DeferredObjectsStack
	{
		/**
		 * List of registered objects that must be sent. Note: There is space
		 * for a hard-coded number of entries that should "be enough for
		 * everybody". 
		 */
		private final DeferredObjectEntry[] itsObjects = new DeferredObjectEntry[1024];

		/**
		 * Number of entries in {@link #itsObjects}.
		 */
		private int itsSize = 0;

		public DeferredObjectsStack()
		{
			for (int i = 0; i < itsObjects.length; i++)
			{
				itsObjects[i] = new DeferredObjectEntry();
			}
		}

		public void push(int aRequestorId, long aId, Object aObject, long aTimestamp)
		{
			itsObjects[itsSize++].set(aRequestorId, aId, aObject, aTimestamp);
		}

		public boolean isEmpty()
		{
			return itsSize == 0;
		}

		/**
		 * Determines if the element at the top of the stack has the specified
		 * requestor id.
		 */
		public boolean isAvailable(int aRequestorId)
		{
			if (isEmpty()) return false;
			return itsObjects[itsSize - 1].requestorId == aRequestorId;
		}

		public DeferredObjectEntry pop()
		{
			return itsObjects[--itsSize];
		}
	}

	private static class DeferredObjectEntry
	{
		public int requestorId;
		public long id;
		public Object object;
		public long timestamp;

		public void set(int aRequestorId, long aId, Object aObject, long aTimestamp)
		{
			requestorId = aRequestorId;
			id = aId;
			object = aObject;
			timestamp = aTimestamp;
		}
	}

	/**
	 * A stack of newly created objects whose type must be registered
	 * @author gpothier
	 */
	private static class RegisteredRefObjectsQueue
	{
		/**
		 * List of registered objects that must be sent.
		 */
		private final NakedLinkedList<Object> itsObjects = new NakedLinkedList<Object>();

		public void push(Object aObject, long aId, Class<?> aClass, long aTimestamp)
		{
			itsObjects.addFirst(new RefObjectEntry(aObject, aId, aClass, aTimestamp));
		}

		public boolean isEmpty()
		{
			return itsObjects.size() == 0;
		}

		public RefObjectEntry pop()
		{
			RefObjectEntry result = (RefObjectEntry)itsObjects.getLastEntry();
			itsObjects.remove(result);
			return result;
		}
	}

	private static class RefObjectEntry extends NakedLinkedList.Entry<Object>
	{
		public long id;
		public Class<?> cls;
		public long timestamp;

		public RefObjectEntry(Object aObject, long aId, Class<?> aClass, long aTimestamp)
		{
		        super(aObject);
			id = aId;
			cls = aClass;
			timestamp = aTimestamp;
		}
	}


}
