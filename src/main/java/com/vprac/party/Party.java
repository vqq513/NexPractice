package com.vprac.party;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Runtime party state. Parties are intentionally session-only and are disbanded when empty. */
public final class Party {
    private final UUID id;
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    private UUID leader;
    private boolean open;
    private String selectedKit = "sword";
    /** Integer.MAX_VALUE represents an unlimited party capacity. */
    private int maxMembers = 4;

    Party(UUID id, UUID leader) {
        this.id = id;
        this.leader = leader;
        this.members.add(leader);
    }

    public UUID id() { return id; }
    public UUID leader() { return leader; }
    public boolean open() { return open; }
    public String selectedKit() { return selectedKit; }
    public int size() { return members.size(); }
    public int maxMembers() { return maxMembers; }
    public boolean hasUnlimitedMembers() { return maxMembers == Integer.MAX_VALUE; }
    public boolean isFull() { return !hasUnlimitedMembers() && members.size() >= maxMembers; }
    public String maxMembersDisplay() { return hasUnlimitedMembers() ? "Unlimited" : Integer.toString(maxMembers); }
    public boolean contains(UUID uuid) { return members.contains(uuid); }
    public Set<UUID> members() { return Collections.unmodifiableSet(members); }

    void setLeader(UUID leader) { this.leader = leader; }
    void setOpen(boolean open) { this.open = open; }
    void setSelectedKit(String selectedKit) { this.selectedKit = selectedKit; }
    void setMaxMembers(int maxMembers) {
        this.maxMembers = maxMembers == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(2, maxMembers);
    }
    boolean add(UUID uuid) { return members.add(uuid); }
    boolean remove(UUID uuid) { return members.remove(uuid); }
}
