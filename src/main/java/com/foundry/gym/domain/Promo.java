package com.foundry.gym.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * One item on the public home page: a photo, an Instagram post or a promotion.
 * The photo is either an https address or a small image stored as a data URL.
 */
@Entity
@Table(name = "promo")
public class Promo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PHOTO, INSTAGRAM or PROMO. */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(length = 500)
    private String body;

    @Lob
    @Column(name = "image")
    private String image;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    /** Whether the tile is drawn with a border on the home page. */
    @Column(nullable = false)
    private boolean bordered = true;

    /** Whether the title is shown on the tile (it is still used as the photo description). */
    @Column(name = "title_visible", nullable = false)
    private boolean titleVisible = true;

    /** Announcement cards only: the full text, which may contain simple HTML. The UI sanitises it before showing it. */
    @Lob
    @Column(name = "content")
    private String content;

    /** The item is no longer shown from this date on (null = no limit). */
    @Column(name = "hide_on")
    private LocalDate hideOn;

    /** Tile width in grid columns, 1 to 4; 0 = automatic. */
    @Column(name = "col_span", nullable = false)
    private int colSpan;

    /** Tile height in grid rows, 1 to 3; 0 = automatic. */
    @Column(name = "row_span", nullable = false)
    private int rowSpan;

    /** Tile background colour as #rrggbb (null or empty = default). */
    @Column(name = "bg_color", length = 7)
    private String bgColor;

    /** Tile background picture: an https address or an uploaded image as a data URL. */
    @Lob
    @Column(name = "bg_image")
    private String bgImage;

    protected Promo() {
    }

    public Promo(String kind, String title, String body, String image, String linkUrl, int sortOrder, boolean active) {
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.image = image;
        this.linkUrl = linkUrl;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getLinkUrl() { return linkUrl; }
    public void setLinkUrl(String linkUrl) { this.linkUrl = linkUrl; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isTitleVisible() { return titleVisible; }
    public void setTitleVisible(boolean titleVisible) { this.titleVisible = titleVisible; }
    public boolean isBordered() { return bordered; }
    public void setBordered(boolean bordered) { this.bordered = bordered; }
    public LocalDate getHideOn() { return hideOn; }
    public void setHideOn(LocalDate hideOn) { this.hideOn = hideOn; }
    public String getBgColor() { return bgColor; }
    public void setBgColor(String bgColor) { this.bgColor = bgColor; }
    public String getBgImage() { return bgImage; }
    public void setBgImage(String bgImage) { this.bgImage = bgImage; }
    public int getColSpan() { return colSpan; }
    public void setColSpan(int colSpan) { this.colSpan = colSpan; }
    public int getRowSpan() { return rowSpan; }
    public void setRowSpan(int rowSpan) { this.rowSpan = rowSpan; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
