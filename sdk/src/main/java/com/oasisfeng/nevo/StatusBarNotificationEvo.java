/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo;

import android.app.Notification;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.support.annotation.RestrictTo.Scope.LIBRARY_GROUP;

/**
 * Ease the code across Nevolution with following features:
 * <ul>
 *   <li>Automatic proxy for heavy notification instance as lazy binder.</li>
 *   <li>Methods for decorator to alter package, tag, ID, key and group key.</li>
 * </ul>
 *
 * Two states: wrapper and proxy (via binder)
 *
 * Created by Oasis on 2015/1/18.
 */
public class StatusBarNotificationEvo extends StatusBarNotification {

	public static StatusBarNotificationEvo from(final StatusBarNotification sbn) {
		if (sbn instanceof StatusBarNotificationEvo) return (StatusBarNotificationEvo) sbn;
		return new StatusBarNotificationEvo(sbn.getPackageName(), null, sbn.getId(), sbn.getTag(), getUid(sbn),
				0, 0, sbn.getNotification(), sbn.getUser(), sbn.getPostTime());
	}

	/** Clone the data fields only (suppliers, notification cache will not be cloned and holder */
	@Override public StatusBarNotificationEvo clone() {
		final StatusBarNotificationEvo clone = from(super.clone());
		clone.tag = tag; clone.id = id; clone.tag_decorated = tag_decorated;
		clone.updateKey();
		return clone;
	}

	public StatusBarNotificationEvo(final String pkg, final String opPkg, final int id, final String tag,
									final int uid, final int initialPid, final int score,
									final Notification notification, final UserHandle user, final long postTime) {
		super(pkg, opPkg, id, tag, uid, initialPid, score, notification, user, postTime);
		holder = new NotificationHolder(notification);
	}

	public StatusBarNotificationEvo setTag(final @Nullable String tag) {
		if (equal(tag, this.tag)) return this;
		if (equal(tag, super.getTag())) {			// Equal to the original tag
			this.tag = null; tag_decorated = false;		// Clear the tag decoration
		} else {
			this.tag = tag; tag_decorated = true;
		}
		updateKey();
		return this;
	}

	public StatusBarNotificationEvo setId(final int id) {
		if (this.id != null) {		// Previously overridden already
			if (id == this.id) return this;
			if (id == super.getId()) this.id = null;	// Equal to the original ID, reset the overridden value
			else this.id = id;
		} else {	// No overridden value yet
			if (id == super.getId()) return this;
			this.id = id;
		}
		updateKey();
		return this;
	}

	private void updateKey() {
		if (tag_decorated || id != null) {		// Initial PID and score has no contribution to generated key.
			final StatusBarNotification sbn = new StatusBarNotification(getPackageName(), null, getId(), getTag(),
					getUid(this), 0, 0, super.getNotification(), getUser(), getPostTime());
			if (SDK_INT >= N) sbn.setOverrideGroupKey(getOverrideGroupKey());
			key = sbn.getKey();
		} else key = null;
	}

	@Override public String getTag() { return tag_decorated ? tag : super.getTag(); }
	@Override public int getId() { return id != null ? id : super.getId(); }
	@Override public String getKey() { return key != null ? key : super.getKey(); }
	public String getOriginalKey() { return super.getKey(); }
	public String getOriginalTag() { return super.getTag(); }
	public int getOriginalId() { return super.getId(); }

	/**
	 * Beware, calling this method on remote instance will retrieve the whole instance, which is inefficient and slow.
	 * This local instance will also be marked as "dirty", greatly increasing the cost of future {@link #writeToParcel(Parcel, int)},
	 * even if nothing is actually changed.
	 *
	 * @deprecated Consider using {@link #notification()} whenever possible to avoid the overhead of this method.
	 */
	@Deprecated @Override public Notification getNotification() {
		try {
			if (holder == null) return super.getNotification();	// holder is null only if called by super constructor StatusBarNotification().
			if (holder instanceof INotification.Stub) return holder.get();	// Direct fetch for local instance
			if (notification == null) try {
				final long begin = SystemClock.uptimeMillis();
				notification = holder.get();
				final long elapse = SystemClock.uptimeMillis() - begin;
				Log.w(TAG, "Retrieving the whole instance of remote notification spent " + elapse + "ms");
				if (notification != null)
					notification.extras.setClassLoader(StatusBarNotificationEvo.class.getClassLoader());    // For our parcelable classes
			} catch (final RuntimeException e) {
				Log.e(TAG, "Failed to retrieve notification: " + getKey());
				throw e;
			}
		} catch (final RemoteException e) { throw new RuntimeException(e); }
		return notification;
	}

	/** Get the interface of notification for modification, to avoid the overhead of get the whole notification */
	public INotification notification() {
		return holder;
	}

	@Override public boolean isOngoing() {
		try {
			return (notification().getFlags() & Notification.FLAG_ONGOING_EVENT) != 0;
		} catch (final RemoteException e) { throw new IllegalStateException(e); }
	}

	@Override public boolean isClearable() {
		try {
			final int flags = notification().getFlags();
			return (flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR)) == 0;
		} catch (final RemoteException e) { throw new IllegalStateException(e); }
	}

	@RestrictTo(LIBRARY_GROUP) static int getUid(final StatusBarNotification sbn) {
		if (sMethodGetUid != null)
			try { return (int) sMethodGetUid.invoke(sbn); } catch (final Exception ignored) {}
		if (sFieldUid != null)
			try { return (int) sFieldUid.get(sbn); } catch (final IllegalAccessException ignored) {}
		// TODO: PackageManager.getPackageUid()
		Log.e(TAG, "Incompatible ROM: StatusBarNotification");
		return 0;
	}
	private static final @Nullable Method sMethodGetUid;
	private static final @Nullable Field sFieldUid;
	static {
		Method method = null; Field field = null;
		try {
			method = StatusBarNotification.class.getMethod("getUid");
		} catch (final NoSuchMethodException ignored) {}
		sMethodGetUid = method;
		if (method == null) try {       // If no such method, try accessing the field
			field = StatusBarNotification.class.getDeclaredField("uid");
			field.setAccessible(true);
		} catch (final NoSuchFieldException ignored) {}
		sFieldUid = field;
	}

	/** Tell {@link #writeToParcel(Parcel, int)} to perform incremental write-back. */
	@RestrictTo(LIBRARY_GROUP) public void setIncrementalWriteToParcel() {
		mIncrementalWriteToParcel = true;
	}

	/** Write all fields except the Notification which is passed as IPC holder */
	@Override public void writeToParcel(final Parcel out, final int flags) {
		if ((flags & PARCELABLE_WRITE_RETURN_VALUE) == 0 || ! mIncrementalWriteToParcel) {
			out.writeInt(PARCEL_MAGIC);
			out.writeString(getPackageName());
			out.writeInt(super.getId());
			if (super.getTag() != null) {
				out.writeInt(1);
				out.writeString(super.getTag());
			} else out.writeInt(0);
			out.writeInt(getUid(this));
			getUser().writeToParcel(out, flags);
			out.writeLong(getPostTime());
			out.writeStrongInterface(notification == null ? holder : new NotificationHolder(notification));	// The local copy of notification is "dirty" (possibly modified), hence needs to be updated.
		} else out.writeInt(PARCEL_MAGIC_REPLY);
		// Shared between full write and incremental write-back.
		if (id != null) {
			out.writeInt(1);
			out.writeInt(id);
		} else out.writeInt(0);
		if (tag_decorated) {
			out.writeInt(1);
			out.writeString(tag);
		} else out.writeInt(0);
	}

	public void readFromParcel(final Parcel reply) {
		if (reply.readInt() != PARCEL_MAGIC_REPLY) throw new IllegalArgumentException();
		if (reply.readInt() == 0) id = null;
		else id = reply.readInt();
		tag_decorated = reply.readInt() != 0;
		if (tag_decorated) tag = reply.readString();
		updateKey();
	}

    // Parcel written by plain StatusBarNotification
	private StatusBarNotificationEvo(final Parcel in, final @Nullable INotification holder) {
		super(in);
		//noinspection deprecation
		this.holder = holder != null ? holder : new NotificationHolder(getNotification());
	}

	private StatusBarNotificationEvo(final Parcel in) {		// PARCEL_MAGIC is already read in createFromParcel()
		super(in.readString(), null, in.readInt(), in.readInt() != 0 ? in.readString() : null, in.readInt(), 0, 0,
				NULL_NOTIFICATION, UserHandle.readFromParcel(in), in.readLong());
		holder = INotification.Stub.asInterface(in.readStrongBinder());
		if (in.readInt() == 0) id = null;
		else id = in.readInt();
		tag_decorated = in.readInt() != 0;
		if (tag_decorated) tag = in.readString();
		updateKey();
	}

	public static final Parcelable.Creator<StatusBarNotificationEvo> CREATOR = new Parcelable.Creator<StatusBarNotificationEvo>() {

		@Override public StatusBarNotificationEvo createFromParcel(final Parcel source) {
			final int pos = source.dataPosition();
			final int magic = source.readInt();
			//noinspection deprecation
			if (magic == PARCEL_MAGIC) return new StatusBarNotificationEvo(source);
			// Then it should be an instance of StatusBarNotification, rewind and un-parcel in that way.
			source.setDataPosition(pos);
			return new StatusBarNotificationEvo(source, null);
		}

		public StatusBarNotificationEvo[] newArray(final int size) { return new StatusBarNotificationEvo[size]; }
	};

	private static boolean equal(final @Nullable Object a, final @Nullable Object b) {
		return a == b || (a != null && a.equals(b));
	}

	@Override public String toString() {
		final StringBuilder string = new StringBuilder("StatusBarNotificationEvo(key=");
		string.append(getOriginalKey());
		if (key != null) string.append(" -> ").append(key);
		string.append(": ");
		if (holder instanceof Binder) try { string.append(holder.get()); } catch (final RemoteException ignored) {}	// Should never happen
		else string.append("remote");
		string.append(')');
		return string.toString();
	}

	private String tag;     // Null is allowed, that's why "tag_decorated" is introduced.
    private @Nullable Integer id;
    private boolean tag_decorated;
    private final INotification holder;
	private transient String key;
	private transient @Nullable Notification notification;	// Cache of remote notification to avoid expensive duplicate fetch.
	private boolean mIncrementalWriteToParcel;

	private static final int PARCEL_MAGIC = "NEVO".hashCode();  // TODO: Are they really magic enough?
	private static final int PARCEL_MAGIC_REPLY = "NEVO.REPLY".hashCode();
	private static final Notification NULL_NOTIFICATION = new Notification();	// Must be placed before VOID to avoid NPE.
	private static final String TAG = "Nevo.SBNE";
}
