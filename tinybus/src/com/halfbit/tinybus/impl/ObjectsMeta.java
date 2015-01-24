/*
 * Copyright (C) 2014, 2015 Sergej Shafarenka, halfbit.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.halfbit.tinybus.impl;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;

import com.halfbit.tinybus.Produce;
import com.halfbit.tinybus.Subscribe;

public class ObjectsMeta {

	//-- static classes and interfaces
	
	public static class EventCallback {
		
		public EventCallback(Method method, Subscribe ann) {
			this.method = method;
			this.mode = ann.mode();
			this.queue = ann.queue();
		}
		
		public final Method method;
		public final int mode;
		public final String queue;
		
	}
	
	/** Implementation of this callback handles actual event dispatching. */
	public static interface EventDispatchCallback {
		void dispatchEvent(EventCallback eventCallback, Object receiver, Object event) throws Exception;
	}
	
	//-- implementation
	
	private final HashMap<Class<? extends Object>/*event class*/, EventCallback> mEventCallbacks
		= new HashMap<Class<? extends Object>, EventCallback>();
	
	private HashMap<Class<? extends Object>/*event class*/, Method> mProducerCallbacks;
	
	public ObjectsMeta(Object obj) {
		final Method[] methods = obj.getClass().getMethods();
		
		Class<?>[] params;
		EventCallback callback;
		Subscribe ann;
		for (Method method : methods) {
			if (method.isBridge()) continue;
			
			ann = method.getAnnotation(Subscribe.class);
			if (ann != null) {
				params = method.getParameterTypes();
				callback = mEventCallbacks.put(params[0], new EventCallback(method, ann));
				if (callback != null) {
					throw new IllegalArgumentException("Only one @Subscriber can be defined "
							+ "per an event type in the same class. Event type: " 
							+ params[0] + ". Class: " + obj.getClass());
				}
				
			} else if (method.isAnnotationPresent(Produce.class)) {
				if (mProducerCallbacks == null) {
					mProducerCallbacks = new HashMap<Class<? extends Object>, Method>();
				}
				mProducerCallbacks.put(method.getReturnType(), method);
			}
		}
	}

	public EventCallback getEventCallback(Class<?> eventClass) {
		return mEventCallbacks.get(eventClass);
	}

	public void dispatchEvents(
			Object obj,
			HashMap<Class<? extends Object>, HashSet<Object>> receivers,
			HashMap<Class<? extends Object>, ObjectsMeta> metas,
			EventDispatchCallback callback) throws Exception {
		
		if (mProducerCallbacks == null) {
			return; // there is no producers for this event type
		}
		
		Iterator<Entry<Class<? extends Object>, Method>> 
			producerCallbacks = mProducerCallbacks.entrySet().iterator();

		Object event;
		ObjectsMeta meta;
		HashSet<Object> targetReceivers;
		Class<? extends Object> eventClass;
		Entry<Class<? extends Object>, Method> producerCallback;
		
		while (producerCallbacks.hasNext()) {
			producerCallback = producerCallbacks.next();
			eventClass = producerCallback.getKey();
			
			targetReceivers = receivers.get(eventClass);
			if (targetReceivers != null && targetReceivers.size() > 0) {
				event = produceEvent(eventClass, obj);
				if (event != null) {
					for (Object receiver : targetReceivers) {
						meta = metas.get(receiver.getClass());
						meta.dispatchEventIfCallbackExists(eventClass, event, receiver, callback);
					}
				}
			}
		}
		
	}

	public void dispatchEvents(
			HashMap<Class<? extends Object>, Object> producers,
			Object receiver,
			HashMap<Class<? extends Object>, ObjectsMeta> metas,
			EventDispatchCallback callback) throws Exception {

		Iterator<Class<? extends Object>> 
			eventClasses = mEventCallbacks.keySet().iterator();
		
		Object event;
		ObjectsMeta meta;
		Object producer;
		Class<? extends Object> eventClass;
		
		while (eventClasses.hasNext()) {
			eventClass = eventClasses.next();
			producer = producers.get(eventClass);
			if (producer != null) {
				meta = metas.get(producer.getClass());
				event = meta.produceEvent(eventClass, producer);
				if (event != null) {
					dispatchEventIfCallbackExists(eventClass, event, receiver, callback);
				}
			}
		}

	}

	private Object produceEvent(Class<? extends Object> eventClass, 
			Object producer) throws Exception {
		return mProducerCallbacks.get(eventClass).invoke(producer);
	}

	public void dispatchEventIfCallbackExists(Class<? extends Object> eventClass, 
			Object event, Object receiver, EventDispatchCallback callback) throws Exception {
		EventCallback eventCallback = mEventCallbacks.get(eventClass);
		if (eventCallback != null) {
			callback.dispatchEvent(eventCallback, receiver, event);
		}
	}
	
	public void unregisterFromProducers(Object obj,
			HashMap<Class<? extends Object>, Object>producers) {
		
		if (mProducerCallbacks == null) {
			return; // no need to unregister, as there is no @Produce methods 
		}
		
		Class<? extends Object> key;
		final Iterator<Class<? extends Object>> keys = mProducerCallbacks.keySet().iterator();
		while (keys.hasNext()) {
			key = keys.next();
			if (producers.remove(key) == null) {
				throw new IllegalArgumentException(
						"Unable to unregister producer, because it wasn't registered before, " + obj);
			}
		}
	}

	public boolean hasRegisteredObject(Object obj, 
			HashMap<Class<? extends Object>, HashSet<Object>> receivers,
			HashMap<Class<? extends Object>, Object> producers) {

		boolean registered = false;
		
		Iterator<Class<? extends Object>> keys;
		HashSet<Object> eventReceivers;
		
		// check receivers
		keys = mEventCallbacks.keySet().iterator();
		while (keys.hasNext()) {
			eventReceivers = receivers.get(keys.next());
			if (eventReceivers != null) {
				registered = eventReceivers.contains(obj);
				if (registered) {
					break;
				}
			}
		}		

		if (!registered) {
			// check producers
			if (mProducerCallbacks != null) {
				keys = mProducerCallbacks.keySet().iterator();
				while (keys.hasNext()) {
					registered = producers.containsKey(keys.next());
					if (registered) {
						break;
					}
				}
			}
		}
		
		return registered;
	}
	
	public void registerAtProducers(Object obj,
			HashMap<Class<? extends Object>, Object> producers) {

		if (mProducerCallbacks == null) {
			return; // this object has no @Produce methods
		}
		
		Class<? extends Object> key;
		final Iterator<Class<? extends Object>> keys = mProducerCallbacks.keySet().iterator();
		while (keys.hasNext()) {
			key = keys.next();
			if (producers.put(key, obj) != null) {
				throw new IllegalArgumentException(
						"Unable to register producer, because another producer is already registered, " + obj);
			}
		}
	}

	public void registerAtReceivers(Object obj,
			HashMap<Class<? extends Object>, HashSet<Object>> receivers) {
		
		Iterator<Class<? extends Object>> keys = mEventCallbacks.keySet().iterator();
		
		Class<? extends Object> key;
		HashSet<Object> eventReceivers;
		
		while (keys.hasNext()) {
			key = keys.next();
			eventReceivers = receivers.get(key);
			if (eventReceivers == null) {
				eventReceivers = new HashSet<Object>();
				receivers.put(key, eventReceivers);
			}
			if (!eventReceivers.add(obj)) {
				throw new IllegalArgumentException(
						"Unable to registered receiver because it has already been registered: " + obj);
			}
		}
	}

	public void unregisterFromReceivers(Object obj,
			HashMap<Class<? extends Object>, HashSet<Object>> receivers) {
		Iterator<Class<? extends Object>> keys = mEventCallbacks.keySet().iterator();
		
		Class<? extends Object> key;
		HashSet<Object> eventReceivers;
		boolean fail;
		while (keys.hasNext()) {
			key = keys.next();
			eventReceivers = receivers.get(key);
			if (eventReceivers == null) {
				fail = true;
			} else {
				fail = !eventReceivers.remove(obj);
			}
			if (fail) {
				throw new IllegalArgumentException(
						"Unregistering receiver which was not registered before: " + obj);
			}
		}
	}
}	
