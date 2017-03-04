package com.alexstyl.specialdates.service;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.support.annotation.NonNull;

import com.alexstyl.specialdates.ErrorTracker;
import com.alexstyl.specialdates.Optional;
import com.alexstyl.specialdates.SQLArgumentBuilder;
import com.alexstyl.specialdates.contact.AndroidContactsProvider;
import com.alexstyl.specialdates.contact.Contact;
import com.alexstyl.specialdates.contact.ContactNotFoundException;
import com.alexstyl.specialdates.contact.ContactsProvider;
import com.alexstyl.specialdates.date.ContactEvent;
import com.alexstyl.specialdates.date.Date;
import com.alexstyl.specialdates.date.DateComparator;
import com.alexstyl.specialdates.date.DateDisplayStringCreator;
import com.alexstyl.specialdates.date.DateParseException;
import com.alexstyl.specialdates.date.TimePeriod;
import com.alexstyl.specialdates.events.database.EventColumns;
import com.alexstyl.specialdates.events.database.EventTypeId;
import com.alexstyl.specialdates.events.database.PeopleEventsContract;
import com.alexstyl.specialdates.events.database.PeopleEventsContract.PeopleEvents;
import com.alexstyl.specialdates.events.namedays.NamedayPreferences;
import com.alexstyl.specialdates.events.namedays.calendar.resource.NamedayCalendarProvider;
import com.alexstyl.specialdates.events.peopleevents.ContactEventsOnADate;
import com.alexstyl.specialdates.events.peopleevents.EventType;
import com.alexstyl.specialdates.events.peopleevents.PeopleNamedaysCalculator;
import com.alexstyl.specialdates.events.peopleevents.StandardEventType;
import com.alexstyl.specialdates.util.DateParser;
import com.novoda.notils.exception.DeveloperError;
import com.novoda.notils.logger.simple.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PeopleEventsProvider {

    private static final String DATE_FROM = "substr(" + PeopleEvents.DATE + ",-5) >= ?";
    private static final String DATE_TO = "substr(" + PeopleEvents.DATE + ",-5) <= ?";
    private static final String DATE_BETWEEN_IGNORING_YEAR = DATE_FROM + " AND " + DATE_TO;
    private static final String[] PEOPLE_PROJECTION = new String[]{PeopleEvents.DATE};
    private static final String[] PROJECTION = {
            PeopleEvents.CONTACT_ID,
            PeopleEvents.DEVICE_EVENT_ID,
            PeopleEvents.DATE,
            PeopleEvents.EVENT_TYPE,
    };

    private final ContactsProvider contactsProvider;
    private final ContentResolver resolver;
    private final NamedayPreferences namedayPreferences;
    private final PeopleNamedaysCalculator peopleNamedaysCalculator;
    private final CustomEventProvider customEventProvider;

    public static PeopleEventsProvider newInstance(Context context) {
        AndroidContactsProvider contactsProvider = AndroidContactsProvider.get(context);
        ContentResolver resolver = context.getContentResolver();
        NamedayPreferences namedayPreferences = NamedayPreferences.newInstance(context);
        NamedayCalendarProvider namedayCalendarProvider = NamedayCalendarProvider.newInstance(context.getResources());
        PeopleNamedaysCalculator peopleNamedaysCalculator = new PeopleNamedaysCalculator(
                namedayPreferences,
                namedayCalendarProvider,
                contactsProvider
        );
        CustomEventProvider customEventProvider = new CustomEventProvider(resolver);
        return new PeopleEventsProvider(contactsProvider, resolver, namedayPreferences, peopleNamedaysCalculator, customEventProvider);
    }

    private PeopleEventsProvider(ContactsProvider contactsProvider,
                                 ContentResolver resolver,
                                 NamedayPreferences namedayPreferences,
                                 PeopleNamedaysCalculator peopleNamedaysCalculator, CustomEventProvider customEventProvider) {
        this.contactsProvider = contactsProvider;
        this.resolver = resolver;
        this.namedayPreferences = namedayPreferences;
        this.peopleNamedaysCalculator = peopleNamedaysCalculator;
        this.customEventProvider = customEventProvider;
    }

    public List<ContactEvent> getCelebrationDateOn(Date date) {
        TimePeriod timeDuration = TimePeriod.between(date, date);
        List<ContactEvent> contactEvents = fetchStaticEventsBetween(timeDuration);

        if (namedayPreferences.isEnabled()) {
            List<ContactEvent> namedaysContactEvents = peopleNamedaysCalculator.loadSpecialNamedaysBetween(timeDuration);
            contactEvents.addAll(namedaysContactEvents);
        }
        return contactEvents;

    }

    public List<ContactEvent> getContactEventsFor(TimePeriod timeDuration) {
        // todo this one
        List<ContactEvent> contactEvents = fetchStaticEventsBetween(timeDuration);

        if (namedayPreferences.isEnabled()) {
            List<ContactEvent> namedaysContactEvents = peopleNamedaysCalculator.loadSpecialNamedaysBetween(timeDuration);
            contactEvents.addAll(namedaysContactEvents);
        }
        return Collections.unmodifiableList(contactEvents);
    }

    private List<ContactEvent> fetchStaticEventsBetween(TimePeriod timeDuration) {
        List<ContactEvent> contactEvents = new ArrayList<>();
        Cursor cursor = queryEventsFor(timeDuration);
        throwIfInvalid(cursor);
        while (cursor.moveToNext()) {
            try {
                ContactEvent contactEvent = getContactEventFrom(cursor);
                contactEvents.add(contactEvent);
            } catch (ContactNotFoundException e) {
                Log.w(e);
            }
        }
        cursor.close();
        return contactEvents;
    }

    private Cursor queryEventsFor(TimePeriod timeDuration) {
        if (isWithinTheSameYear(timeDuration)) {
            return queryPeopleEvents(timeDuration, PeopleEvents.DATE + " ASC");
        } else {
            return queryForBothYearsIn(timeDuration);
        }
    }

    private Cursor queryPeopleEvents(TimePeriod timePeriod, String sortOrder) {
        String[] selectArgs = new String[]{
                SQLArgumentBuilder.dateWithoutYear(timePeriod.getStartingDate()),
                SQLArgumentBuilder.dateWithoutYear(timePeriod.getEndingDate()),
        };

        Cursor cursor = resolver.query(
                PeopleEvents.CONTENT_URI,
                PROJECTION,
                DATE_BETWEEN_IGNORING_YEAR,
                selectArgs,
                sortOrder
        );
        if (isInvalid(cursor)) {
            ErrorTracker.track(new IllegalStateException("People Events returned invalid cursor"));
        }
        return cursor;
    }

    private Cursor queryForBothYearsIn(TimePeriod timeDuration) {
        TimePeriod firstHalf = firstHalfOf(timeDuration);
        Cursor[] cursors = new Cursor[2];
        cursors[0] = queryPeopleEvents(firstHalf, PeopleEvents.DATE + " ASC");
        TimePeriod secondHalf = secondHalfOf(timeDuration);
        cursors[1] = queryPeopleEvents(secondHalf, PeopleEvents.DATE + " ASC");
        return new MergeCursor(cursors);
    }

    private static TimePeriod firstHalfOf(TimePeriod timeDuration) {
        return TimePeriod.between(
                timeDuration.getStartingDate(),
                Date.endOfYear(timeDuration.getStartingDate().getYear())
        );
    }

    private static TimePeriod secondHalfOf(TimePeriod timeDuration) {
        return TimePeriod.between(
                Date.startOfTheYear(timeDuration.getEndingDate().getYear()),
                timeDuration.getEndingDate()
        );
    }

    private boolean isWithinTheSameYear(TimePeriod timeDuration) {
        return timeDuration.getStartingDate().getYear() == timeDuration.getEndingDate().getYear();
    }

    private ContactEvent getContactEventFrom(Cursor cursor) throws ContactNotFoundException {
        long contactId = getContactIdFrom(cursor);
        Contact contact = contactsProvider.getOrCreateContact(contactId);
        Date date = getDateFrom(cursor);
        EventType eventType = getEventType(cursor);

        Optional<Long> eventId = getDeviceEventIdFrom(cursor);
        return new ContactEvent(eventId, eventType, date, contact);
    }

    public ContactEventsOnADate getCelebrationsClosestTo(Date date) {
        Optional<Date> closestStaticDate = findClosestStaticEventDateFrom(date);
        ContactEventsOnADate staticEvents = getStaticContactEventsFor(closestStaticDate.get());
        ContactEventsOnADate dynamicEvents = getDyanmicEvents(date, closestStaticDate);

        if (DateComparator.INSTANCE.compare(closestStaticDate.get(), dynamicEvents.getDate()) == 0) {
            return ContactEventsOnADate.createFrom(dynamicEvents.getDate(), combine(dynamicEvents.getEvents(), staticEvents.getEvents()));
        } else if (DateComparator.INSTANCE.compare(closestStaticDate.get(), dynamicEvents.getDate()) > 0) {
            return dynamicEvents;
        } else {
            return staticEvents;
        }
    }

    private ContactEventsOnADate getDyanmicEvents(Date date, Optional<Date> closestStaticDate) {
        if (namedayPreferences.isEnabled()) {
            Optional<Date> closestDynamicDate = findClosestDynamicEventDateTo(date);
            if (closestDynamicDate.isPresent()) {
                List<ContactEvent> namedaysContactEvents = peopleNamedaysCalculator.loadSpecialNamedaysOn(closestDynamicDate.get());
                return ContactEventsOnADate.createFrom(closestDynamicDate.get(), namedaysContactEvents);
            }
        }
        return ContactEventsOnADate.createFrom(closestStaticDate.get(), Collections.<ContactEvent>emptyList());
    }

    private Optional<Date> findClosestStaticEventDateFrom(Date date) {
        Cursor cursor = queryDateClosestTo(date);
        try {
            if (cursor.moveToFirst()) {
                Date closestDate = getDateFrom(cursor);
                return new Optional<>(closestDate);
            }
            return Optional.absent();
        } finally {
            cursor.close();
        }
    }

    private Optional<Date> findClosestDynamicEventDateTo(Date date) {
        List<ContactEvent> contactEvents = peopleNamedaysCalculator.loadSpecialNamedaysBetween(TimePeriod.between(date, date.addWeek(4)));
        if (contactEvents.size() > 0) {
            return new Optional<>(contactEvents.get(0).getDate());
        }
        return Optional.absent();
    }

    private ContactEventsOnADate getStaticContactEventsFor(Date date) {
        List<ContactEvent> contactEvents = new ArrayList<>();
        Cursor cursor = resolver.query(
                PeopleEvents.CONTENT_URI,
                null,
                PeopleEvents.DATE + " = ?",
                getSelectArgs(date),
                PeopleEvents.CONTACT_ID
        );

        while (cursor.moveToNext()) {
            long contactId = getContactIdFrom(cursor);
            try {
                Contact contact = contactsProvider.getOrCreateContact(contactId);
                EventType eventType = getEventType(cursor);
                Optional<Long> deviceEventId = getDeviceEventIdFrom(cursor);

                ContactEvent event = new ContactEvent(deviceEventId, eventType, date, contact);
                contactEvents.add(event);
            } catch (Exception e) {
                ErrorTracker.track(e);
            }
        }
        cursor.close();
        return ContactEventsOnADate.createFrom(date, contactEvents);
    }

    private static final Uri PEOPLE_EVENTS = PeopleEvents.CONTENT_URI;

    private Cursor queryDateClosestTo(Date date) {
        // select * from annual_events WHERE substr(date,3) >= '03-04' ORDER BY substr(date,3) asc LIMIT 1
        return resolver.query(
                PEOPLE_EVENTS,
                PEOPLE_PROJECTION,
                substr(PeopleEvents.DATE) + " >= ?",
                monthAndDayOf(date),
                substr(PeopleEvents.DATE) + " ASC LIMIT 1"
        );
    }

    @NonNull
    private String substr(String datetete) {
        return "substr(" + datetete + ",3) ";
    }

    private String[] monthAndDayOf(Date date) {
        return new String[]{
                DateDisplayStringCreator.INSTANCE.stringOfNoYear(date)
        };
    }

    private String[] getSelectArgs(Date date) {
        return new String[]{date.toShortDate()};
    }

    private static void throwIfInvalid(Cursor cursor) {
        if (isInvalid(cursor)) {
            throw new RuntimeException("Invalid cursor");
        }
    }

    private static boolean isInvalid(Cursor cursor) {
        return cursor == null || cursor.isClosed();
    }

    private static Date getDateFrom(Cursor cursor) {
        int index = cursor.getColumnIndexOrThrow(PeopleEventsContract.PeopleEvents.DATE);
        String text = cursor.getString(index);
        return from(text);
    }

    private static long getContactIdFrom(Cursor cursor) {
        int contactIdIndex = cursor.getColumnIndexOrThrow(PeopleEvents.CONTACT_ID);
        return cursor.getLong(contactIdIndex);
    }

    private EventType getEventType(Cursor cursor) {
        int eventTypeIndex = cursor.getColumnIndexOrThrow(PeopleEvents.EVENT_TYPE);
        @EventTypeId int rawEventType = cursor.getInt(eventTypeIndex);
        if (rawEventType == EventColumns.TYPE_CUSTOM) {
            Optional<Long> deviceEventIdFrom = getDeviceEventIdFrom(cursor);
            if (deviceEventIdFrom.isPresent()) {
                return queryCustomEvent(deviceEventIdFrom.get());
            }
            return StandardEventType.OTHER;
        }
        return StandardEventType.fromId(rawEventType);
    }

    private EventType queryCustomEvent(long deviceId) {
        return customEventProvider.getEventWithId(deviceId);
    }

    private static Date from(String text) {
        try {
            return DateParser.INSTANCE.parse(text);
        } catch (DateParseException e) {
            e.printStackTrace();
            throw new DeveloperError("Invalid date stored to database. [" + text + "]");
        }
    }

    private static Optional<Long> getDeviceEventIdFrom(Cursor cursor) {
        int eventId = cursor.getColumnIndexOrThrow(PeopleEvents.DEVICE_EVENT_ID);
        long deviceEventId = cursor.getLong(eventId);
        if (isALegitEventId(deviceEventId)) {
            return Optional.absent();
        }
        return new Optional<>(deviceEventId);
    }

    private static boolean isALegitEventId(long deviceEventId) {
        return deviceEventId == -1;
    }

    private static <T> List<T> combine(List<T> listA, List<T> listB) {
        List<T> contactEvents = new ArrayList<>();
        contactEvents.addAll(listA);
        contactEvents.addAll(listB);
        return contactEvents;
    }

}
