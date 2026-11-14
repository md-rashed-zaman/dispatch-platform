/**
 * Photos and documents attached to work orders, stored in S3-compatible object storage. Files go straight
 * from the phone to storage through short-lived presigned URLs; the API only decides who may upload what,
 * checks what arrived, and keeps the metadata (T15).
 */
package dev.rashed.dispatch.media;
