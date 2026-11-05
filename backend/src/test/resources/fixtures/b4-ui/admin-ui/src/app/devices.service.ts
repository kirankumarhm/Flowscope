import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

@Injectable()
export class DevicesService {
  constructor(private http: HttpClient) {}

  list() {
    return this.http.get(environment.serverUri + 'api/3.0.0/devices/');
  }
}
