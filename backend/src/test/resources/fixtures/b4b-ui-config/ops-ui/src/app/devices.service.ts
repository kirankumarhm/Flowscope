import { HttpClient } from '@angular/common/http';
import { Configuration } from './shared/configuration';
import { environment } from '../environments/environment';

export class DevicesService {
  constructor(private http: HttpClient, private config: Configuration) {}

  list() {
    return this.http.get(environment.serverUri + this.config.deviceUri);
  }
}
